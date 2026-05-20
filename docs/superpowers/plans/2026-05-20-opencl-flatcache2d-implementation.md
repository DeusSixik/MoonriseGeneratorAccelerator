# OpenCL FlatCache 2D Inputs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-safe OpenCL FlatCache 2D prefill path for `BlendAlpha` / `BlendOffset` external inputs while preserving the CPU fallback.

**Architecture:** Keep existing finalDensity wave/final kernels unchanged. Classify direct external slots as trusted constants, FlatCache 2D tables, or CPU fallback; when FlatCache 2D is available, run a small OpenCL prefill stage that writes the existing compact slot-major `slotBuffer` before downstream kernels execute.

**Tech Stack:** Java 21, JUnit 5, Minecraft `DensityFunction` / `NoiseChunk.FlatCache`, LWJGL OpenCL 1.2, `DfcOpenClRuntime`, `DfcOpenClGeneratedNoiseSource`, `DfcOpenClDeviceContext`.

---

## File Structure

- Modify `common/src/main/java/dev/sixik/generator_accelerator/common/noise/NoiseChunk$FlatCache$FlatArray.java`: expose FlatCache 2D metadata.
- Modify `common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk$FlatCache$OptimizeFlatArray.java`: return metadata from the owning `NoiseChunk`.
- Modify `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`: classify slots, build prefill payloads, route fallback, add diagnostics.
- Modify `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java`: generate the FlatCache 2D prefill kernel.
- Modify `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`: upload FlatCache payloads and enqueue the prefill kernel.
- Modify `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`: add fixture, classifier, source, and CPU mirror tests.

---

### Task 1: FlatCache Metadata Access

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/noise/NoiseChunk$FlatCache$FlatArray.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk$FlatCache$OptimizeFlatArray.java`
- Test: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Write the failing fixture test**

Add this test:

```java
@Test
void flatCacheLikeTestFixtureExposes2dMetadata() {
    FlatCache2dDensityFunction function = new FlatCache2dDensityFunction(
            new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, -3, 7);

    assertArrayEquals(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, function.bts$getArray());
    assertEquals(2, function.bts$getSide());
    assertEquals(-3, function.bts$getFirstNoiseX());
    assertEquals(7, function.bts$getFirstNoiseZ());
}
```

Add a test helper that implements `DensityFunction.SimpleFunction`, `DfcCellCacheAccess`, and `NoiseChunk$FlatCache$FlatArray`; its `dfc$tryDirectRead` must compute `values[(blockX >> 2 - firstNoiseX) * side + (blockZ >> 2 - firstNoiseZ)]` and return `DfcCacheFastPath.CACHE_MISS` out of bounds.

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.flatCacheLikeTestFixtureExposes2dMetadata
```

Expected: compile failure because `bts$getSide`, `bts$getFirstNoiseX`, and `bts$getFirstNoiseZ` are not present on `NoiseChunk$FlatCache$FlatArray`.

- [ ] **Step 3: Extend the interface**

Change `NoiseChunk$FlatCache$FlatArray.java` to include these default methods:

```java
default int bts$getSide() {
    return -1;
}

default int bts$getFirstNoiseX() {
    return Integer.MIN_VALUE;
}

default int bts$getFirstNoiseZ() {
    return Integer.MIN_VALUE;
}
```

- [ ] **Step 4: Implement metadata in the mixin**

Add to `MixinNoiseChunk$FlatCache$OptimizeFlatArray.java`:

```java
@Override
public int bts$getSide() {
    return this.field_36611.noiseSizeXZ + 1;
}

@Override
public int bts$getFirstNoiseX() {
    return this.field_36611.firstNoiseX;
}

@Override
public int bts$getFirstNoiseZ() {
    return this.field_36611.firstNoiseZ;
}
```

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.flatCacheLikeTestFixtureExposes2dMetadata
git add common/src/main/java/dev/sixik/generator_accelerator/common/noise/NoiseChunk$FlatCache$FlatArray.java common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk$FlatCache$OptimizeFlatArray.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Expose FlatCache 2D metadata"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 2: External Slot Classification

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Write failing classifier tests**

Add tests for these three cases:

```java
@Test
void directExternalSlotClassificationTreatsUniformFlatCacheAsConstant() {
    DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
            new FlatCache2dDensityFunction(new double[]{7.0D, 7.0D, 7.0D, 7.0D}, 2, 0, 0));

    DfcOpenClRuntime.ExternalInputClassification classification =
            DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

    assertFalse(classification.requiresCpuFallback());
    assertEquals(DfcOpenClRuntime.ExternalInputKind.CONSTANT, classification.slots()[0].kind());
    assertEquals(7.0D, classification.slots()[0].constantValue());
}

@Test
void directExternalSlotClassificationTreatsNonUniformFlatCacheAsFlatCache2d() {
    DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
            new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, -1, 5));

    DfcOpenClRuntime.ExternalInputClassification classification =
            DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

    assertFalse(classification.requiresCpuFallback());
    assertEquals(1, classification.flatTables().length);
    assertEquals(DfcOpenClRuntime.ExternalInputKind.FLAT_CACHE_2D, classification.slots()[0].kind());
    assertEquals(-1, classification.flatTables()[0].firstNoiseX());
    assertEquals(5, classification.flatTables()[0].firstNoiseZ());
}

@Test
void directExternalSlotClassificationFallsBackForInvalidFlatCacheArray() {
    DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
            new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D}, 2, 0, 0));

    DfcOpenClRuntime.ExternalInputClassification classification =
            DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

    assertTrue(classification.requiresCpuFallback());
    assertEquals(DfcOpenClRuntime.ExternalInputKind.CPU_FALLBACK, classification.slots()[0].kind());
}
```

Add `openClPlanWithOneExternal(DensityFunction extern)` using the existing `OpenClCompiledPlan` test constructor pattern with `externalSlots = {false, true, false}` and `markerExternIndices = {-1, 0, -1}`.

- [ ] **Step 2: Run the failing tests**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.directExternalSlotClassificationTreatsUniformFlatCacheAsConstant --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.directExternalSlotClassificationTreatsNonUniformFlatCacheAsFlatCache2d --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.directExternalSlotClassificationFallsBackForInvalidFlatCacheArray
```

Expected: compile failure because classifier API is missing.

- [ ] **Step 3: Add classifier API**

Add `import dev.sixik.generator_accelerator.common.noise.NoiseChunk$FlatCache$FlatArray;` and these package-private nested types in `DfcOpenClRuntime`:

```java
enum ExternalInputKind { CONSTANT, FLAT_CACHE_2D, CPU_FALLBACK }
record FlatCache2dTable(double[] values, int side, int firstNoiseX, int firstNoiseZ) {}
record ExternalInputSlot(int slot, int compactIndex, ExternalInputKind kind,
                         double constantValue, int tableIndex, String fallbackReason) {}
record ExternalInputClassification(ExternalInputSlot[] slots, FlatCache2dTable[] flatTables,
                                   boolean requiresCpuFallback) {}
```

Add `classifyDirectExternalSlotBufferInputs(OpenClCompiledPlan plan, int[] slots, int[] compactIndices)` that:

```java
// 1. uses constantDensityFunctionValue(extern) first;
// 2. accepts FlatCache only when extern instanceof DfcCellCacheAccess
//    and extern instanceof NoiseChunk$FlatCache$FlatArray;
// 3. validates values != null, side > 0, values.length >= side * side;
// 4. scans values for exact raw-bit uniformity and emits CONSTANT when uniform;
// 5. deduplicates non-uniform tables by values array identity;
// 6. emits CPU_FALLBACK and requiresCpuFallback=true for invalid/unknown externs.
```

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2 again.

Then:

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Classify OpenCL external inputs"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 3: FlatCache 2D Prefill Kernel Source

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Write failing source test**

Add:

```java
@Test
void flatCache2dPrefillSourceUsesFloorQuartCoordinatesAndSlotMajorOutput() {
    DfcOpenClGeneratedNoiseSource.BuildResult source =
            DfcOpenClGeneratedNoiseSource.buildFlatCache2dSlotBufferPrefill();

    assertTrue(source.source().contains("dfc_floor_div4"));
    assertTrue(source.source().contains("int quart_x = dfc_floor_div4(block_x);"));
    assertTrue(source.source().contains("int flat_index = local_x * side + local_z;"));
    assertTrue(source.source().contains("slot_buffer[compact_index * n + gid]"));
}
```

- [ ] **Step 2: Run the failing source test**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.flatCache2dPrefillSourceUsesFloorQuartCoordinatesAndSlotMajorOutput
```

Expected: compile failure because `buildFlatCache2dSlotBufferPrefill` is missing.

- [ ] **Step 3: Add source builder**

Add `static BuildResult buildFlatCache2dSlotBufferPrefill()` to `DfcOpenClGeneratedNoiseSource`. The generated kernel must have this signature:

```c
__kernel void dfc_generated(
    __global const double *flat_cache_values,
    __global const int *slot_compact_indices,
    __global const int *slot_table_indices,
    __global const int *table_offsets,
    __global const int *table_sides,
    __global const int *table_first_x,
    __global const int *table_first_z,
    __global double *slot_buffer,
    int firstBlockX, int firstBlockY, int firstBlockZ,
    int cellWidth, int cellHeight, int cells, int slot_count, int n)
```

The kernel body must:

```c
static int dfc_floor_div4(int v) {
    return v >= 0 ? (v >> 2) : -(((-v) + 3) >> 2);
}

int gid = get_global_id(0);
if (gid >= n) return;
int cell_volume = cellWidth * cellWidth * cellHeight;
int cell = gid / cell_volume;
int in_cell = gid - cell * cell_volume;
int plane = in_cell % (cellWidth * cellWidth);
int in_x = plane / cellWidth;
int in_z = plane - in_x * cellWidth;
int cell_x = cell & 31;
int cell_z = cell >> 5;
int block_x = firstBlockX + cell_x * cellWidth + in_x;
int block_z = firstBlockZ + cell_z * cellWidth + in_z;
int quart_x = dfc_floor_div4(block_x);
int quart_z = dfc_floor_div4(block_z);
for (int i = 0; i < slot_count; i++) {
    int table = slot_table_indices[i];
    int side = table_sides[table];
    int local_x = quart_x - table_first_x[table];
    int local_z = quart_z - table_first_z[table];
    if (local_x < 0 || local_z < 0 || local_x >= side || local_z >= side) continue;
    int flat_index = local_x * side + local_z;
    int compact_index = slot_compact_indices[i];
    slot_buffer[compact_index * n + gid] = flat_cache_values[table_offsets[table] + flat_index];
}
```

- [ ] **Step 4: Run source test and commit**

Run the command from Step 2 again.

Then:

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Generate FlatCache 2D prefill kernel"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 4: CPU Mirror For FlatCache 2D Prefill

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Write failing CPU mirror test**

Add:

```java
@Test
void flatCache2dCpuPrefillMatchesExistingExternalComputePath() {
    FlatCache2dDensityFunction extern = new FlatCache2dDensityFunction(
            new double[]{10.0D, 11.0D, 12.0D, 13.0D}, 2, 0, 7);
    DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(extern);
    DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
            new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                    new byte[0], new double[0],
                    new byte[0], new double[0], new double[0], new double[0],
                    new int[0], new int[0], new double[0], new double[0],
                    3, 0, 0, 0, 64, 28, 4, 2, 1,
                    0.0D, new double[0], 32);
    DfcOpenClRuntime.ExternalInputClassification classification =
            DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});
    double[] actual = new double[request.n()];

    DfcOpenClRuntime.fillFlatCache2dSlotBufferInputsForTest(request, classification, actual);

    double[] rowMajor = DfcOpenClRuntime.fillExternalSlots(plan, request, 3);
    double[] expected = new double[request.n()];
    DfcOpenClRuntime.copyDirectExternalSlotBufferInputs(request, rowMajor, expected, new int[]{1}, new int[]{0});
    assertArrayEquals(expected, actual);
}
```

- [ ] **Step 2: Run failing CPU mirror test**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.flatCache2dCpuPrefillMatchesExistingExternalComputePath
```

Expected: compile failure because `fillFlatCache2dSlotBufferInputsForTest` is missing.

- [ ] **Step 3: Add CPU mirror**

Add `static void fillFlatCache2dSlotBufferInputsForTest(...)` to `DfcOpenClRuntime`. It must iterate `ExternalInputKind.FLAT_CACHE_2D` slots, compute `blockX` and `blockZ` with existing `cellBlockX/Z`, compute the table index with Java `>> 2`, and write `values[slot.compactIndex() * request.n() + element]`.

- [ ] **Step 4: Run CPU mirror test and commit**

Run the command from Step 2 again.

Then:

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Mirror FlatCache 2D prefill on CPU"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 5: DeviceContext Upload And Enqueue

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`

- [ ] **Step 1: Add prefill payload record**

Add near `SlabVmNoiseCellGridRequest`:

```java
record FlatCache2dPrefill(double[] flatValues, int[] slotCompactIndices, int[] slotTableIndices,
                          int[] tableOffsets, int[] tableSides, int[] tableFirstNoiseX,
                          int[] tableFirstNoiseZ, int slotCount) {}
```

Add a `flatCache2dPrefill(ExternalInputClassification classification)` builder in `DfcOpenClRuntime` that packs table arrays contiguously, fills `tableOffsets`, and emits one slot entry per `FLAT_CACHE_2D` input.

- [ ] **Step 2: Add reusable buffers**

Add `BufferState` fields for values, slot compact indices, slot table indices, table offsets, table sides, table first X, and table first Z. Release them in `releaseDeviceBuffers()` using the existing buffer release helper.

- [ ] **Step 3: Add enqueue helper**

Add `enqueueFlatCache2dPrefill(GeneratedNoiseKernel kernel, SlabVmNoiseCellGridRequest request, FlatCache2dPrefill prefill, long slotBuffer, PointerBuffer globalWorkSize, IntBuffer err)`. It must upload all arrays, set kernel args in the same order as Task 3's kernel signature, and enqueue with `request.n` global size.

If missing, add:

```java
private static long intBytes(int count) {
    return Math.multiplyExact((long) count, Integer.BYTES);
}
```

and `writeIntArray(...)` mirroring the existing double-array upload helper.

- [ ] **Step 4: Extend final-output eval methods**

Add nullable parameters to `evalFinalOutputStagesToFinalOutput` and `evalFinalOutputStagesToFinalOutputTrace`:

```java
GeneratedNoiseKernel flatCache2dKernel,
FlatCache2dPrefill flatCache2dPrefill
```

After `globalWorkSize.put(0, request.n)` and before downstream stages:

```java
if (flatCache2dKernel != null && flatCache2dPrefill != null) {
    enqueueFlatCache2dPrefill(flatCache2dKernel, request, flatCache2dPrefill, slotBuffer, globalWorkSize, err);
}
```

- [ ] **Step 5: Compile check and commit**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.flatCache2dPrefillSourceUsesFloorQuartCoordinatesAndSlotMajorOutput
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java
git commit -m "Upload FlatCache 2D prefill inputs"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 6: Runtime Integration With Fallback

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Extend `FinalOutputSlotBufferInputs`**

Change the record to:

```java
private record FinalOutputSlotBufferInputs(double[] values, FinalOutputExternalPrefillTrace trace,
                                           DfcOpenClDeviceContext.FlatCache2dPrefill flatCache2dPrefill,
                                           String flatCache2dFallbackReason) {}
```

Update old constructors to pass `null, ""`.

- [ ] **Step 2: Route direct external inputs**

In `fillFinalOutputDirectExternalSlotBufferInputs`, call `classifyDirectExternalSlotBufferInputs`. If `requiresCpuFallback`, keep existing `copyDirectExternalSlotBufferInputs`. If not, fill constants into `values`, attach `flatCache2dPrefill`, and leave FlatCache slots zero until the GPU prefill kernel writes them.

- [ ] **Step 3: Compile prefill kernel when needed**

In the final output dispatch path, when `initialInputs.flatCache2dPrefill() != null`, compile:

```java
DfcOpenClDeviceContext.GeneratedNoiseKernel flatCache2dKernel =
        context.compileGeneratedNoiseKernelCached(
                DfcOpenClGeneratedNoiseSource.buildFlatCache2dSlotBufferPrefill());
```

Pass the kernel and payload into `evalFinalOutputStagesToFinalOutput` and trace variants. Wrap this with a catch that records `flatCache2dFallbackReason` and re-runs the existing CPU-filled path.

- [ ] **Step 4: Add regression test**

Add:

```java
@Test
void directExternalSlotBufferInputsConstantIsFalseWhenFlatCache2dUploadIsNeeded() {
    DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
            new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, 0, 0));

    assertFalse(DfcOpenClRuntime.directExternalSlotBufferInputsConstant(plan, new int[]{1}));
}
```

- [ ] **Step 5: Test and commit**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Use FlatCache 2D prefill with fallback"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 7: Diagnostics

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [ ] **Step 1: Write failing diagnostics test**

Add:

```java
@Test
void externalPrefillTraceReportsFlatCache2dCounters() {
    String details = DfcOpenClRuntime.describeFlatCache2dPrefillForTest(
            new DfcOpenClDeviceContext.FlatCache2dPrefill(
                    new double[]{1.0D, 2.0D, 3.0D, 4.0D},
                    new int[]{0, 1}, new int[]{0, 0}, new int[]{0},
                    new int[]{2}, new int[]{0}, new int[]{0}, 2),
            "");

    assertTrue(details.contains("flatCache2dSlots=2"));
    assertTrue(details.contains("flatCache2dBuffers=1"));
    assertTrue(details.contains("flatCache2dBytes=32"));
}
```

- [ ] **Step 2: Run failing diagnostics test**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.externalPrefillTraceReportsFlatCache2dCounters
```

Expected: compile failure because `describeFlatCache2dPrefillForTest` is missing.

- [ ] **Step 3: Add formatter and append to bench output**

Add `describeFlatCache2dPrefillForTest(FlatCache2dPrefill prefill, String fallbackReason)` returning `flatCache2dSlots`, `flatCache2dBuffers`, `flatCache2dBytes`, and `flatCache2dFallbackReason` when non-empty. Append it to finalDensity output messages near `externalPrefillTrace` and `slotBufferBytes`.

- [ ] **Step 4: Test and commit**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.externalPrefillTraceReportsFlatCache2dCounters
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java
git commit -m "Report FlatCache 2D prefill diagnostics"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

### Task 8: Full Verification

**Files:**
- Verify only; no planned source edits.

- [ ] **Step 1: Run whitespace check**

Run:

```powershell
git diff --check
```

Expected: no errors. LF-to-CRLF warnings are acceptable in this repository.

- [ ] **Step 2: Run targeted tests**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClCompiledPlanRegistryTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerChunkDepsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run build**

Run:

```powershell
.\gradlew.bat build -x test
```

Expected: `BUILD SUCCESSFUL`. Known remap warnings about `hasAppended` and `criterion` are acceptable.

- [ ] **Step 4: Check git status**

Run:

```powershell
git status --short --branch
```

Expected: branch ahead of origin with no uncommitted source changes.

---

## Self-Review

- Spec coverage: classifier, data-based constant path, FlatCache 2D GPU prefill, CPU fallback, diagnostics, and verification commands are covered by Tasks 1-8.
- Placeholder scan: no `TBD`, incomplete task, or unresolved requirement remains.
- Type consistency: `ExternalInputClassification`, `ExternalInputSlot`, `FlatCache2dTable`, and `FlatCache2dPrefill` are introduced before later tasks use them.
