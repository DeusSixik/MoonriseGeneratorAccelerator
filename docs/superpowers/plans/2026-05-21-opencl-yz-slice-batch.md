# OpenCL Y/Z Slice Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce runtime OpenCL finalDensity dispatch overhead by batching one full Y/Z slice per filler instead of one Y-column per z-index.

**Architecture:** Add a `CELL_GRID_LAYOUT_Y_Z_SLICE` request layout that maps `cell = zIndex * cellCountY + yIndex` with fixed X and varying Y/Z. `MixinNoiseChunk` caches one full slice per cell-cache filler and current `cellStartBlockX`; copy-back extracts the requested `(zIndex, yIndex)` cell. If the slice dispatch is ineligible or fails, the current CPU fallback remains intact.

**Tech Stack:** Java 17, Sponge Mixin, Minecraft `NoiseChunk`, LWJGL OpenCL, generated OpenCL C kernels, JUnit 5, Gradle.

---

## File Map

- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
  - Add tests for slice sizing, element indexing, copy-back, and Java coordinate mapping.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
  - Add slice layout constant, helper methods, slice request builder, slice public API, and generalized final-output dispatch.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`
  - Allow the new layout in request validation.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java`
  - Ensure generated coordinate calls keep accepting the layout and new stride field if needed.
- Modify: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`
  - Add OpenCL-side `DFC_CELL_GRID_LAYOUT_Y_Z_SLICE` coordinate mapping.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk.java`
  - Replace per-z OpenCL column cache with per-filler Y/Z slice cache.

---

### Task 1: Slice Helper Tests

- [ ] **Step 1: Write failing tests**

Add tests that assert:

```java
assertEquals(4 * 4 * 8 * 32 * 25,
        DfcOpenClRuntime.runtimeHybridSliceCellValues(4, 8, 32, 25));
assertEquals(0,
        DfcOpenClRuntime.runtimeSliceBatchElementIndex(0, 0, 0, 32, 4, 8));
assertEquals(32 * 128 + 16,
        DfcOpenClRuntime.runtimeSliceBatchElementIndex(1, 0, 1, 32, 4, 8));
```

Also fill a synthetic batch and verify `copyRuntimeSliceBatchCell(batch, y, z, out, cellCountY, cellWidth, cellHeight)` copies exactly one Java-order cell.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest
```

Expected: compilation fails because slice helpers do not exist.

- [ ] **Step 3: Implement helpers**

Add `CELL_GRID_LAYOUT_Y_Z_SLICE = 2`, `runtimeHybridSliceCellValues`, `runtimeHybridSliceSlotValues`, `runtimeHybridSliceMeetsMinimum`, `runtimeSliceBatchElementIndex`, and `copyRuntimeSliceBatchCell`.

- [ ] **Step 4: Verify GREEN**

Run the same test class and confirm the new helper tests pass.

### Task 2: Slice Coordinate Layout

- [ ] **Step 1: Write failing coordinate test**

Create a `SlabVmNoiseCellGridRequest` with layout `Y_Z_SLICE`, `firstBlockX=100`, `firstBlockY=-64`, `firstBlockZ=200`, `cellWidth=4`, `cellHeight=8`, `cells=64`, and `cellStride=32` or equivalent. Assert element `z=1,y=2,local java index=16` maps to `x=100`, `y=-64 + 2*8 + 7`, `z=200 + 1*4`.

- [ ] **Step 2: Verify RED**

Run the coordinate test and confirm it fails for missing layout/stride behavior.

- [ ] **Step 3: Implement Java/OpenCL mapping**

Add request stride support if required, validate it in `DfcOpenClDeviceContext`, update all record construction sites, and mirror the same mapping in `dfc_slab_vm.cl`.

- [ ] **Step 4: Verify GREEN**

Run `DfcOpenClGeneratedNoiseSourceTest`.

### Task 3: Runtime Slice Dispatch

- [ ] **Step 1: Write API visibility/fallback tests**

Assert `tryFillFinalDensityHybridSlice(CompiledDensityFunction, double[], NoiseChunk, int, int, int, int)` is public and returns `false` for null/invalid arguments without throwing.

- [ ] **Step 2: Verify RED**

Run the new tests and confirm compile failure.

- [ ] **Step 3: Implement slice API**

Build a slice request with `firstBlockZ = chunk.cellStartBlockZ + firstCellZ * cellWidth`, `cells = cellCountY * cellCountZ`, and the slice layout. Reuse the full GPU final-output dispatch path so final output is read directly into the slice output buffer.

- [ ] **Step 4: Verify GREEN**

Run the OpenCL generated source test class.

### Task 4: NoiseChunk Slice Cache

- [ ] **Step 1: Modify cache fields**

Store one slice buffer per cache filler, keyed by `cellStartBlockX`, filler identity, first z, and z count. Remove the per-z cache key from successful reuse.

- [ ] **Step 2: Dispatch and copy**

In `selectCellYZ`, dispatch the slice on first use, then copy `(zIndex, yIndex)` from the cached slice. If dispatch returns `false`, call `fast.dfc$fillCell(values, self)` exactly as before.

- [ ] **Step 3: Invalidate safely**

Clear slice cache on `swapSlices()` and when shape/filler/key changes.

- [ ] **Step 4: Compile**

Run `./gradlew.bat compileJava`.

### Task 5: Verification And Commit

- [ ] **Step 1: Run focused tests**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest
```

- [ ] **Step 2: Run build check**

```powershell
.\gradlew.bat build -x test
```

- [ ] **Step 3: Sanity checks**

```powershell
git diff --check
```

- [ ] **Step 4: Commit**

```powershell
git add docs/superpowers/plans/2026-05-21-opencl-yz-slice-batch.md common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java common/src/main/resources/dfc_opencl/dfc_slab_vm.cl common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk.java
git commit -m "Batch OpenCL final density across Z slices"
```

## Acceptance Criteria

- Runtime finalDensity batch attempts drop sharply compared with per-z column batching.
- `failed=0` and CPU fallback remains available for rejects/failures.
- Full GPU final-output diagnostics still validate with low error.
- `finalDensity batch elements` remain comparable while dispatch count is much lower.
