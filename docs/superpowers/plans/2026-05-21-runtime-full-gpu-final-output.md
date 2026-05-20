# Runtime Full GPU Final Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route `NoiseChunk` finalDensity column batches through the already validated GPU final-output pipeline so worldgen no longer falls back to slow CPU/JNI or CPU finish.

**Architecture:** Reuse the diagnostic final-output stage planner/evaluator, but feed it a real `runtimeNoiseColumnCellGridRequest` and write directly into the column `batch` output. Keep OpenCL/context work synchronized, keep CPU prefill limited to external/FlatCache inputs, and fail-soft back to CPU when a GPU plan cannot be built.

**Tech Stack:** Java 21, JUnit 5, Gradle, LWJGL OpenCL wrappers, existing `DfcOpenClRuntime`/`DfcOpenClDeviceContext` final-output kernels.

---

### Task 1: Guard Runtime Path Selection

**Files:**
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`

- [x] Add a test proving a final-output-capable runtime plan is not rejected just because CPU finish has residual computed/noise slots.
- [x] Add a test proving the old CPU finish safety guard remains false for residual CPU finish.
- [x] Implement a runtime final-output availability helper that checks final-output stages, not CPU-finish stages.

### Task 2: Build Runtime Final-Output Dispatch

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`

- [x] Extract the final-output stage planning from `compiledPlanChunkAllWavesFusedFinalOutput` into a helper that accepts `plan`, `descriptor`, `waves`, chunk ranges, and `request`.
- [x] Add a `RuntimeFinalOutputPlan`/build result that contains `slotBufferSlotCount`, initial slot buffer, stage builds, final kernel source, and flat-cache prefill metadata.
- [x] In `dispatchFinalDensityHybridColumn`, try full GPU final-output first; do not call `fillRuntimeHybridFinalDensityColumn` on this path.
- [x] Preserve fail-soft behavior: any build/dispatch exception records batch failure, disables hybrid for session only when OpenCL dispatch itself fails, otherwise returns false for CPU fallback.

### Task 3: Verify and Commit

**Files:**
- Test: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`

- [x] Run targeted tests for runtime final-output selection.
- [x] Run focused OpenCL tests.
- [x] Run `./gradlew.bat build -x test`.
- [x] Run `git diff --check` and BOM check.
- [x] Commit with message `Use GPU final output for OpenCL column batches`.
