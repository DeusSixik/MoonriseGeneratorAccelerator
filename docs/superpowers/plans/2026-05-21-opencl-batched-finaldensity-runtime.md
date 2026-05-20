# OpenCL Batched FinalDensity Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make runtime `NoiseChunk` finalDensity fills use an OpenCL vertical-column batch when that batch crosses the current minimum size, with CPU fallback for every rejected or failed batch.

**Architecture:** Keep the existing per-cell `tryFillFinalDensityHybrid(...)` path intact, and add a separate Y-column batch layout for real `NoiseChunk` runtime use. `MixinNoiseChunk.selectCellYZ` asks the OpenCL runtime to fill all `cellY` values for the active `cellX, cellZ`, caches the batch per cell-cache filler, and copies the requested cell back into vanilla `CacheAllInCell.values`. Generated OpenCL kernels receive a layout flag so diagnostics keep their current synthetic X/Z grid while runtime uses fixed X/Z plus varying Y.

**Tech Stack:** Java 17, Sponge Mixin, Minecraft `NoiseChunk`, LWJGL OpenCL, generated OpenCL C kernels, JUnit 5, Gradle.

---

## File Map

- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
  - Add batch sizing helpers, Y-column request builder, column dispatch, and Java-order copy helpers.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClStats.java`
  - Add batch call/skip/attempt/success/failure counters, public recorder methods, and snapshot fields.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java`
  - Print `DFC OpenCL finalDensity batch: ...` in `/dfc opencl stats`.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`
  - Add `layout` to `SlabVmNoiseCellGridRequest` and pass it to generated kernels.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java`
  - Add `layout` arguments and update FlatCache 2D prefill coordinate generation.
- Modify: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`
  - Add `DFC_CELL_GRID_LAYOUT_XZ` and `DFC_CELL_GRID_LAYOUT_Y_COLUMN` in `dfc_cell_grid_coords`.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk.java`
  - Add per-cache column batch state, invalidation, and copy-back before the existing `dfc$fillCell` fallback.
- Test: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
  - Cover sizing, index mapping, copy-back, coordinate layouts, source generation, and stats.

---

### Task 1: Runtime Batch Helper Tests

**Files:**
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`

- [ ] **Step 1: Write failing tests**

Add after `runtimeHybridSkipsPerCellDispatchBelowMinimumSlotValues()`:

```java
    @Test
    void runtimeHybridColumnBatchMeetsDefaultMinimumWhenScheduledSlotsAreLargeEnough() {
        String oldMin = System.getProperty("dfc.opencl.finalDensityHybridMinSlotValues");
        try {
            System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            assertEquals(4_096, DfcOpenClRuntime.runtimeHybridBatchCellValues(4, 8, 32));
            assertEquals(221_184, DfcOpenClRuntime.runtimeHybridBatchSlotValues(4, 8, 32, 54));
            assertTrue(DfcOpenClRuntime.runtimeHybridBatchMeetsMinimum(4, 8, 32, 54));
            assertFalse(DfcOpenClRuntime.runtimeHybridBatchMeetsMinimum(4, 8, 1, 54));
        } finally {
            if (oldMin == null) {
                System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            } else {
                System.setProperty("dfc.opencl.finalDensityHybridMinSlotValues", oldMin);
            }
        }
    }

    @Test
    void runtimeColumnBatchElementIndexOffsetsCells() {
        int cellWidth = 4;
        int cellHeight = 8;
        int cellVolume = cellWidth * cellWidth * cellHeight;
        assertEquals(0, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 0, cellWidth, cellHeight));
        assertEquals(16, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 1, cellWidth, cellHeight));
        assertEquals(127, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 127, cellWidth, cellHeight));
        assertEquals(cellVolume, DfcOpenClRuntime.runtimeColumnBatchElementIndex(1, 0, cellWidth, cellHeight));
        assertEquals(cellVolume + 16, DfcOpenClRuntime.runtimeColumnBatchElementIndex(1, 1, cellWidth, cellHeight));
    }

    @Test
    void runtimeColumnBatchCopyExtractsOneJavaOrderCell() {
        int cellWidth = 4;
        int cellHeight = 8;
        int cellVolume = cellWidth * cellWidth * cellHeight;
        double[] batch = new double[cellVolume * 3];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 10_000.0D + i;
        }
        double[] cell = new double[cellVolume];
        DfcOpenClRuntime.copyRuntimeColumnBatchCell(batch, 2, cell, cellWidth, cellHeight);
        for (int i = 0; i < cellVolume; i++) {
            assertEquals(10_000.0D + cellVolume * 2 + i, cell[i]);
        }
    }
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeHybridColumnBatchMeetsDefaultMinimumWhenScheduledSlotsAreLargeEnough --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeColumnBatchElementIndexOffsetsCells --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeColumnBatchCopyExtractsOneJavaOrderCell
```

Expected: compilation fails because the helper methods are not present.

- [ ] **Step 3: Add helpers**

In `DfcOpenClRuntime`, add constants near the runtime static fields:

```java
    static final int CELL_GRID_LAYOUT_XZ = 0;
    static final int CELL_GRID_LAYOUT_Y_COLUMN = 1;
```

Add after `runtimeHybridCellValuesCanReachMinimum(...)`:

```java
    static int runtimeHybridBatchCellValues(int cellWidth, int cellHeight, int cellCountY) {
        if (cellWidth <= 0 || cellHeight <= 0 || cellCountY <= 0) {
            return 0;
        }
        return Math.multiplyExact(Math.multiplyExact(cellWidth, cellWidth),
                Math.multiplyExact(cellHeight, cellCountY));
    }

    static int runtimeHybridBatchSlotValues(int cellWidth, int cellHeight, int cellCountY, int scheduledSlots) {
        if (scheduledSlots <= 0) {
            return 0;
        }
        return Math.multiplyExact(runtimeHybridBatchCellValues(cellWidth, cellHeight, cellCountY), scheduledSlots);
    }

    static boolean runtimeHybridBatchMeetsMinimum(int cellWidth, int cellHeight, int cellCountY, int scheduledSlots) {
        return runtimeHybridBatchSlotValues(cellWidth, cellHeight, cellCountY, scheduledSlots)
                >= DfcOpenClConfig.finalDensityHybridMinSlotValues();
    }

    static int runtimeColumnBatchElementIndex(int cellYIndex, int javaFillIndex, int cellWidth, int cellHeight) {
        int cellVolume = Math.multiplyExact(Math.multiplyExact(cellWidth, cellWidth), cellHeight);
        return Math.addExact(Math.multiplyExact(cellYIndex, cellVolume),
                runtimeCellFillElementIndex(javaFillIndex, cellWidth, cellHeight));
    }

    public static void copyRuntimeColumnBatchCell(double[] batchValues, int cellYIndex, double[] cellValues,
                                                  int cellWidth, int cellHeight) {
        int cellVolume = Math.multiplyExact(Math.multiplyExact(cellWidth, cellWidth), cellHeight);
        if (cellYIndex < 0 || batchValues == null || cellValues == null
                || cellValues.length < cellVolume
                || batchValues.length < Math.multiplyExact(cellYIndex + 1, cellVolume)) {
            throw new IllegalArgumentException("invalid runtime column batch copy arguments");
        }
        System.arraycopy(batchValues, Math.multiplyExact(cellYIndex, cellVolume), cellValues, 0, cellVolume);
    }
```

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command again, then:

```powershell
git add common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java
git commit -m "Add OpenCL finalDensity batch helpers"
```

---

### Task 2: Batch Stats And Command Output

**Files:**
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClStats.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java`

- [ ] **Step 1: Write failing stats test**

Add near the runtime hybrid tests:

```java
    @Test
    void runtimeHybridBatchStatsRecordSkipAttemptSuccessAndFailure() {
        DfcOpenClStats.reset();
        DfcOpenClStats.recordHybridBatchCall();
        DfcOpenClStats.recordHybridBatchSkipped("column batch disabled");
        DfcOpenClStats.recordHybridBatchAttempt(32, 4_096);
        DfcOpenClStats.recordHybridBatchSuccess(32, 4_096);
        DfcOpenClStats.recordHybridBatchFailure("device lost");

        DfcOpenClStats.Snapshot snapshot = DfcOpenClStats.snapshot();
        assertEquals(1L, snapshot.hybridBatchCalls());
        assertEquals(1L, snapshot.hybridBatchSkipped());
        assertEquals(1L, snapshot.hybridBatchAttempts());
        assertEquals(1L, snapshot.hybridBatchSucceeded());
        assertEquals(1L, snapshot.hybridBatchFailed());
        assertEquals(64L, snapshot.hybridBatchCells());
        assertEquals(8_192L, snapshot.hybridBatchElements());
        assertEquals("device lost", snapshot.hybridBatchLastSkip());

        DfcOpenClStats.reset();
        assertEquals(0L, DfcOpenClStats.snapshot().hybridBatchCalls());
        assertEquals("", DfcOpenClStats.snapshot().hybridBatchLastSkip());
    }
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeHybridBatchStatsRecordSkipAttemptSuccessAndFailure
```

Expected: compilation fails because batch stats methods and snapshot fields are missing.

- [ ] **Step 3: Add stats implementation**

In `DfcOpenClStats`, add `LongAdder` fields:

```java
    private static final LongAdder HYBRID_BATCH_CALLS = new LongAdder();
    private static final LongAdder HYBRID_BATCH_SKIPPED = new LongAdder();
    private static final LongAdder HYBRID_BATCH_ATTEMPTS = new LongAdder();
    private static final LongAdder HYBRID_BATCH_SUCCEEDED = new LongAdder();
    private static final LongAdder HYBRID_BATCH_FAILED = new LongAdder();
    private static final LongAdder HYBRID_BATCH_CELLS = new LongAdder();
    private static final LongAdder HYBRID_BATCH_ELEMENTS = new LongAdder();
    private static final AtomicReference<String> HYBRID_BATCH_LAST_SKIP = new AtomicReference<>("");
```

Add recorder methods:

```java
    public static void recordHybridBatchCall() {
        HYBRID_BATCH_CALLS.increment();
    }

    public static void recordHybridBatchSkipped(String reason) {
        HYBRID_BATCH_SKIPPED.increment();
        HYBRID_BATCH_LAST_SKIP.set(reason == null || reason.isBlank() ? "skipped" : reason);
    }

    public static void recordHybridBatchAttempt(int cells, int elements) {
        HYBRID_BATCH_ATTEMPTS.increment();
        addHybridBatchShape(cells, elements);
    }

    public static void recordHybridBatchSuccess(int cells, int elements) {
        HYBRID_BATCH_SUCCEEDED.increment();
        addHybridBatchShape(cells, elements);
    }

    public static void recordHybridBatchFailure(String reason) {
        HYBRID_BATCH_FAILED.increment();
        HYBRID_BATCH_LAST_SKIP.set(reason == null || reason.isBlank() ? "failed" : reason);
    }

    private static void addHybridBatchShape(int cells, int elements) {
        if (cells > 0) HYBRID_BATCH_CELLS.add(cells);
        if (elements > 0) HYBRID_BATCH_ELEMENTS.add(elements);
    }
```

Extend `snapshot()`, `reset()`, and `Snapshot` with:

```java
long hybridBatchCalls,
long hybridBatchSkipped,
long hybridBatchAttempts,
long hybridBatchSucceeded,
long hybridBatchFailed,
long hybridBatchCells,
long hybridBatchElements,
String hybridBatchLastSkip
```

- [ ] **Step 4: Add command output**

In `DensityFunctionCompiler.sendOpenClStats`, after the current finalDensity hybrid line:

```java
        source.sendSuccess(() -> Component.literal(
                "DFC OpenCL finalDensity batch: calls=" + stats.hybridBatchCalls()
                        + ", skipped=" + stats.hybridBatchSkipped()
                        + ", attempts=" + stats.hybridBatchAttempts()
                        + ", succeeded=" + stats.hybridBatchSucceeded()
                        + ", failed=" + stats.hybridBatchFailed()
                        + ", cells=" + stats.hybridBatchCells()
                        + ", elements=" + stats.hybridBatchElements()
                        + (stats.hybridBatchLastSkip() == null || stats.hybridBatchLastSkip().isBlank()
                        ? ""
                        : ", lastSkip=" + stats.hybridBatchLastSkip())), false);
```

- [ ] **Step 5: Verify GREEN and commit**

Run the Step 2 command again, then:

```powershell
git add common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClStats.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java
git commit -m "Report OpenCL finalDensity batch stats"
```

---

### Task 3: Add Cell Grid Layout To Requests And Kernels

**Files:**
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java`
- Modify: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`

- [ ] **Step 1: Write failing layout tests**

Add a test request helper in `DfcOpenClGeneratedNoiseSourceTest`:

```java
    private static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest testRequestWithLayout(
            int layout, int firstBlockX, int firstBlockY, int firstBlockZ,
            int cellWidth, int cellHeight, int cells) {
        int n = cellWidth * cellWidth * cellHeight * cells;
        return new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                DfcOpenClSlabVmSmoke.bytecode(), DfcOpenClSlabVmSmoke.constants(),
                new byte[0], new double[0], new double[0], new double[0],
                new int[0], new int[0], new double[0], new double[0],
                0, 0, 0,
                firstBlockX, firstBlockY, firstBlockZ,
                cellWidth, cellHeight, cells, layout, 0.0D, new double[n], n);
    }
```

Add tests:

```java
    @Test
    void runtimeCellGridCoordsKeepSyntheticXzLayout() {
        var request = testRequestWithLayout(DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ, 100, 40, 200, 4, 8, 64);
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(0, request));
        assertEquals(47.0D, DfcOpenClRuntime.runtimeCellGridBlockY(0, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(0, request));
        assertEquals(104.0D, DfcOpenClRuntime.runtimeCellGridBlockX(128, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(128, request));
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(32 * 128, request));
        assertEquals(204.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(32 * 128, request));
    }

    @Test
    void runtimeCellGridCoordsMapYColumnLayout() {
        var request = testRequestWithLayout(DfcOpenClRuntime.CELL_GRID_LAYOUT_Y_COLUMN, 100, -64, 200, 4, 8, 32);
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(0, request));
        assertEquals(-57.0D, DfcOpenClRuntime.runtimeCellGridBlockY(0, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(0, request));
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(128, request));
        assertEquals(-49.0D, DfcOpenClRuntime.runtimeCellGridBlockY(128, request));
        assertEquals(203.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(255, request));
    }

    @Test
    void generatedSourcesPassCellGridLayout() {
        String generated = DfcOpenClGeneratedNoiseSource.build(DfcOpenClNoiseDescriptor.synthetic(1, 1), 1).source();
        String flat = DfcOpenClGeneratedNoiseSource.buildFlatCache2dSlotBufferPrefill().source();
        assertTrue(generated.contains("int layout"));
        assertTrue(generated.contains("cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell"));
        assertTrue(flat.contains("int layout"));
        assertTrue(flat.contains("cellWidth, cellHeight, cells, layout"));
    }
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeCellGridCoordsKeepSyntheticXzLayout --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeCellGridCoordsMapYColumnLayout --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.generatedSourcesPassCellGridLayout
```

Expected: compilation fails because request `layout` and coordinate helpers are missing.

- [ ] **Step 3: Add request layout**

In `DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest`, add `int layout` between `cells` and `hoistBase`. Add `request.layout` as an `int` kernel argument after `request.cells` in every generated wave and final-output setup block.

Run:

```powershell
rg -n "new DfcOpenClDeviceContext\.SlabVmNoiseCellGridRequest|request\.cells|first_block_x|dfc_cell_grid_coords" common/src/main/java common/src/test/java common/src/main/resources
```

Every existing diagnostic request gets `DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ`. The runtime column request added in Task 4 gets `CELL_GRID_LAYOUT_Y_COLUMN`.

- [ ] **Step 4: Add layout-aware Java coordinate helpers**

Replace the private `cellBlockX/Y/Z` methods in `DfcOpenClRuntime` with package-visible helpers named `runtimeCellGridBlockX/Y/Z`. Use X/Z synthetic layout for `CELL_GRID_LAYOUT_XZ`; use fixed X/Z and `firstBlockY + cell * cellHeight` for `CELL_GRID_LAYOUT_Y_COLUMN`. Replace all internal calls with the new helper names.

Core formula:

```java
int cellVolume = request.cellWidth() * request.cellWidth() * request.cellHeight();
int cell = element / cellVolume;
int inCell = element - cell * cellVolume;
int planeSize = request.cellWidth() * request.cellWidth();
int yIndex = inCell / planeSize;
int plane = inCell - yIndex * planeSize;
int ix = plane / request.cellWidth();
int iz = plane - ix * request.cellWidth();
```

For `CELL_GRID_LAYOUT_Y_COLUMN`, return:

```java
bx = request.firstBlockX() + ix;
by = request.firstBlockY() + cell * request.cellHeight() + (request.cellHeight() - 1 - yIndex);
bz = request.firstBlockZ() + iz;
```

- [ ] **Step 5: Update OpenCL coordinate helper**

In `dfc_slab_vm.cl`, add:

```c
#define DFC_CELL_GRID_LAYOUT_XZ 0
#define DFC_CELL_GRID_LAYOUT_Y_COLUMN 1
```

Change `dfc_cell_grid_coords` to accept `int layout` after `cells`. Compute `cell`, `y_index`, `ix`, and `iz` with the same formula as Step 4. For Y-column, set `bx/by/bz` to fixed X/Z with Y offset; otherwise keep the existing `cell_x = cell & 31` and `cell_z = cell >> 5`.

- [ ] **Step 6: Update generated source builder**

In `DfcOpenClGeneratedNoiseSource`, add `int layout` after `int cells` in all generated kernel signatures and calls to `dfc_cell_grid_coords`. In `buildFlatCache2dSlotBufferPrefill()`, compute `block_x` and `block_z` through `dfc_cell_grid_coords(..., layout, ...)` instead of reconstructing synthetic `cell_x/cell_z`.

- [ ] **Step 7: Verify GREEN and commit**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest
git add common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClDeviceContext.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSource.java common/src/main/resources/dfc_opencl/dfc_slab_vm.cl
git commit -m "Support OpenCL Y-column cell grid layout"
```

---

### Task 4: Column Batch Runtime API And Dispatch

**Files:**
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`

- [ ] **Step 1: Write failing API test**

Add near `runtimeHybridFastSkipPathIsNotGloballySynchronized()`:

```java
    @Test
    void runtimeHybridColumnApiIsPublicAndNotSynchronized() throws NoSuchMethodException {
        int modifiers = DfcOpenClRuntime.class.getDeclaredMethod(
                "tryFillFinalDensityHybridColumn",
                CompiledDensityFunction.class, double[].class, NoiseChunk.class, int.class, int.class)
                .getModifiers();
        assertTrue(Modifier.isPublic(modifiers));
        assertFalse(Modifier.isSynchronized(modifiers));
    }
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest.runtimeHybridColumnApiIsPublicAndNotSynchronized
```

Expected: compilation fails because `tryFillFinalDensityHybridColumn` does not exist.

- [ ] **Step 3: Add request builder and public API**

In `DfcOpenClRuntime`, add a private builder that creates `SlabVmNoiseCellGridRequest` with:

```java
firstBlockX = chunk.cellStartBlockX
firstBlockY = baseCellY * cellHeight
firstBlockZ = chunk.cellStartBlockZ
cells = cellCountY
layout = CELL_GRID_LAYOUT_Y_COLUMN
n = runtimeHybridBatchCellValues(cellWidth, cellHeight, cellCountY)
```

Add public API:

```java
    public static boolean tryFillFinalDensityHybridColumn(CompiledDensityFunction compiled,
                                                          double[] out,
                                                          NoiseChunk chunk,
                                                          int baseCellY,
                                                          int cellCountY) {
        DfcOpenClStats.recordHybridBatchCall();
        if (!DfcOpenClConfig.finalDensityHybridEnabled()) {
            DfcOpenClStats.recordHybridBatchSkipped("disabled");
            return false;
        }
        if (finalDensityHybridBroken) {
            DfcOpenClStats.recordHybridBatchSkipped("broken");
            return false;
        }
        if (compiled == null || out == null || chunk == null) {
            DfcOpenClStats.recordHybridBatchSkipped("null compiled/out/chunk");
            return false;
        }
        int cellWidth = chunk.cellWidth;
        int cellHeight = chunk.cellHeight;
        int n = runtimeHybridBatchCellValues(cellWidth, cellHeight, cellCountY);
        if (n <= 0 || out.length < n) {
            DfcOpenClStats.recordHybridBatchSkipped("invalid column batch shape");
            return false;
        }
        RuntimeHybridPlan runtimePlan = runtimeHybridPlan(compiled);
        if (!runtimePlan.available()) {
            DfcOpenClStats.recordHybridBatchSkipped(runtimePlan.unavailableReason());
            return false;
        }
        int slotValues = Math.multiplyExact(n, runtimePlan.scheduledSlotCount());
        if (!runtimeHybridBatchMeetsMinimum(cellWidth, cellHeight, cellCountY, runtimePlan.scheduledSlotCount())) {
            DfcOpenClStats.recordHybridBatchSkipped("runtime hybrid column slot values " + slotValues
                    + "<" + DfcOpenClConfig.finalDensityHybridMinSlotValues());
            return false;
        }
        return dispatchFinalDensityHybridColumn(out, chunk, baseCellY, cellCountY,
                cellWidth, cellHeight, n, runtimePlan, slotValues);
    }
```

- [ ] **Step 4: Add synchronized dispatch**

Add `private static synchronized boolean dispatchFinalDensityHybridColumn(...)` mirroring `dispatchFinalDensityHybrid(...)`:

```java
compile runtimePlan.waveSources()
build Y-column request
double[] slotBuffer = new double[slotValues]
DfcOpenClStats.recordHybridBatchAttempt(cellCountY, n)
DfcOpenClStats.recordSlabAttempt(slotValues)
context.evalGeneratedNoiseKernelWavesToSlotBuffer(kernels, runtimePlan.kernelWaves(), request, runtimePlan.scheduledSlotCount(), true, slotBuffer)
fillRuntimeHybridFinalDensityColumn(out, chunk, baseCellY, cellCountY, request, runtimePlan, slotBuffer)
DfcOpenClStats.recordHybridBatchSuccess(cellCountY, n)
return true
```

On `Throwable`, record `recordHybridBatchFailure(errorMessage(throwable))`, record slab failure, set `finalDensityHybridBroken = true`, close context, log, and return `false`.

- [ ] **Step 5: Add CPU final-output fill for batch**

Add `fillRuntimeHybridFinalDensityColumn(...)`. It loops `cellY = 0..cellCountY-1`, then the same Java fill order as `fillRuntimeHybridFinalDensity(...)`: X, Z, descending Y. For each value:

```java
chunk.cellStartBlockY = (baseCellY + cellY) * cellHeight;
chunk.inCellX = inCellX;
chunk.inCellZ = inCellZ;
chunk.inCellY = inCellY;
chunk.arrayIndex = javaIndex;
int element = runtimeColumnBatchElementIndex(cellY, javaIndex, cellWidth, cellHeight);
double bx = chunk.cellStartBlockX + inCellX;
double by = chunk.cellStartBlockY + inCellY;
double bz = chunk.cellStartBlockZ + inCellZ;
out[cellY * cellVolume + javaIndex] = finalDensityValue;
```

Save and restore `cellStartBlockY`, `inCellX`, `inCellY`, `inCellZ`, and `arrayIndex` in a `finally` block.

- [ ] **Step 6: Verify GREEN and commit**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest
git add common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClGeneratedNoiseSourceTest.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java
git commit -m "Add OpenCL finalDensity column batch dispatch"
```

---

### Task 5: NoiseChunk Column Batch Cache

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk.java`

- [ ] **Step 1: Add imports and fields**

Add imports:

```java
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClRuntime;
```

Add `@Unique` fields after `bts$cellCacheValues`:

```java
    @Unique private double[][] bts$openClColumnBatchValues;
    @Unique private int[] bts$openClColumnBatchCellStartBlockX;
    @Unique private int[] bts$openClColumnBatchZIndex;
    @Unique private DfcCellFillAccess[] bts$openClColumnBatchFiller;
```

- [ ] **Step 2: Initialize fields**

At the end of `bts$initCellCacheArrays()`:

```java
        this.bts$openClColumnBatchValues = new double[length][];
        this.bts$openClColumnBatchCellStartBlockX = new int[length];
        this.bts$openClColumnBatchZIndex = new int[length];
        this.bts$openClColumnBatchFiller = new DfcCellFillAccess[length];
        Arrays.fill(this.bts$openClColumnBatchCellStartBlockX, Integer.MIN_VALUE);
        Arrays.fill(this.bts$openClColumnBatchZIndex, Integer.MIN_VALUE);
```

- [ ] **Step 3: Add invalidation helper**

Add before `selectCellYZ(...)`:

```java
    @Unique
    private void bts$invalidateOpenClColumnBatches() {
        if (this.bts$openClColumnBatchValues != null) {
            Arrays.fill(this.bts$openClColumnBatchValues, null);
        }
        if (this.bts$openClColumnBatchCellStartBlockX != null) {
            Arrays.fill(this.bts$openClColumnBatchCellStartBlockX, Integer.MIN_VALUE);
        }
        if (this.bts$openClColumnBatchZIndex != null) {
            Arrays.fill(this.bts$openClColumnBatchZIndex, Integer.MIN_VALUE);
        }
        if (this.bts$openClColumnBatchFiller != null) {
            Arrays.fill(this.bts$openClColumnBatchFiller, null);
        }
    }
```

- [ ] **Step 4: Add copy-or-dispatch helper**

Add before `selectCellYZ(...)`:

```java
    @Unique
    private boolean bts$tryOpenClColumnBatch(int cacheIndex, int yIndex, int zIndex,
                                             DfcCellFillAccess fast, double[] values, NoiseChunk self) {
        if (!(fast instanceof CompiledDensityFunction compiled)) {
            return false;
        }
        int cellVolume = this.cellWidth * this.cellWidth * this.cellHeight;
        int batchLength = cellVolume * this.cellCountY;
        double[] batch = this.bts$openClColumnBatchValues[cacheIndex];
        if (batch != null
                && this.bts$openClColumnBatchCellStartBlockX[cacheIndex] == this.cellStartBlockX
                && this.bts$openClColumnBatchZIndex[cacheIndex] == zIndex
                && this.bts$openClColumnBatchFiller[cacheIndex] == fast
                && batch.length >= batchLength) {
            DfcOpenClRuntime.copyRuntimeColumnBatchCell(batch, yIndex, values, this.cellWidth, this.cellHeight);
            return true;
        }
        if (batch == null || batch.length < batchLength) {
            batch = new double[batchLength];
            this.bts$openClColumnBatchValues[cacheIndex] = batch;
        }
        if (DfcOpenClRuntime.tryFillFinalDensityHybridColumn(compiled, batch, self, this.cellNoiseMinY, this.cellCountY)) {
            this.bts$openClColumnBatchCellStartBlockX[cacheIndex] = this.cellStartBlockX;
            this.bts$openClColumnBatchZIndex[cacheIndex] = zIndex;
            this.bts$openClColumnBatchFiller[cacheIndex] = fast;
            DfcOpenClRuntime.copyRuntimeColumnBatchCell(batch, yIndex, values, this.cellWidth, this.cellHeight);
            return true;
        }
        this.bts$openClColumnBatchValues[cacheIndex] = null;
        this.bts$openClColumnBatchCellStartBlockX[cacheIndex] = Integer.MIN_VALUE;
        this.bts$openClColumnBatchZIndex[cacheIndex] = Integer.MIN_VALUE;
        this.bts$openClColumnBatchFiller[cacheIndex] = null;
        return false;
    }
```

- [ ] **Step 5: Use helper and invalidate on swap**

In `selectCellYZ(...)`, replace:

```java
                fast.dfc$fillCell(values, self);
```

with:

```java
                if (!this.bts$tryOpenClColumnBatch(i, yIndex, zIndex, fast, values, self)) {
                    fast.dfc$fillCell(values, self);
                }
```

At the end of `swapSlices()`, add:

```java
        this.bts$invalidateOpenClColumnBatches();
```

- [ ] **Step 6: Compile and commit**

```powershell
.\gradlew.bat compileJava
git add common/src/main/java/dev/sixik/generator_accelerator/common/noise/mixin/MixinNoiseChunk.java
git commit -m "Use OpenCL column batches in NoiseChunk cell cache"
```

---

### Task 6: Verification And Manual Runtime Proof

**Files:**
- No production edits expected.

- [ ] **Step 1: Run focused tests**

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest
```

Expected: selected tests pass.

- [ ] **Step 2: Run build validation**

```powershell
.\gradlew.bat build -x test
```

Expected: build succeeds.

- [ ] **Step 3: Manual game commands**

Run in chat:

```mcfunction
/dfc opencl probe
/dfc opencl stats reset
```

Generate fresh chunks for 30-60 seconds, then:

```mcfunction
/dfc opencl stats
```

Expected:

```text
DFC OpenCL finalDensity batch: calls=..., skipped=..., attempts=..., succeeded=..., failed=..., cells=..., elements=...
hybridBatchCalls > 0
hybridBatchFailed = 0
```

If eligible finalDensity-sized rebound plans are reached:

```text
hybridBatchAttempts > 0
hybridBatchSucceeded > 0
hybridBatchElements >= hybridBatchSucceeded * 4096
```

- [ ] **Step 4: Re-run router diagnostics**

```mcfunction
/dfc opencl compiledplanexterns
/dfc opencl compiledfinaldensityallwavesoutputcheck 512
/dfc opencl compiledfinaldensityallwavesoutputtracebench 512
/dfc opencl compiledfinaldensityallwavesoutputbench 512
/dfc opencl compiledfinaldensityallwavesoutputnoreadbench 512
```

Expected:

```text
passed=true
validationMaxAbsError <= 1.0e-9
```

`flatCache2dSlots=0` may still appear in router diagnostics because those commands do not pass through `NoiseChunk.wrap`.

---

## Acceptance Criteria

- `/dfc opencl stats` prints a finalDensity batch line.
- Fresh chunk generation records `hybridBatchCalls > 0`.
- Batch failures stay at `0`; failures or skips use CPU fallback.
- Existing OpenCL diagnostics still pass with `passed=true`.
- No production path lowers `dfc.opencl.finalDensityHybridMinSlotValues` to force per-cell dispatch.
