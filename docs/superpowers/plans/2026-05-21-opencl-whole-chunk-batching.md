# OpenCL Whole Chunk Noise Batching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a whole-chunk OpenCL noise pipeline that moves generation granularity from `NoiseChunk` cell slices to chunk-sized GPU jobs with safe fallback.

**Architecture:** Keep the existing finalDensity Y/Z slice path as fallback and diagnostics. Add a new chunk pipeline under `dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk`, starting with disabled-by-default single-chunk output, then direct packed block output, persistent buffers, and aligned multi-chunk batches.

**Tech Stack:** Java 17, Sponge Mixin, Minecraft worldgen classes, LWJGL OpenCL, generated OpenCL C kernels, Gradle, JUnit 5.

---

## File Map

- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRequest.java`
  - Immutable request shape for one chunk or an aligned chunk batch.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkOutputLayout.java`
  - Pure coordinate and output-index mapping helpers.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkResult.java`
  - Safe accessors around density or packed block output.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkStats.java`
  - Counters and snapshot for the chunk pipeline.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
  - Entry point, eligibility checks, fallback decisions, and dispatch orchestration.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkBlockWriter.java`
  - Converts packed GPU output into section writes.
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkBufferCache.java`
  - Owner-thread buffer reuse for chunk jobs after direct output works.
- Create: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`
  - Unit tests for request validation, layout, result access, stats, and fake writer behavior.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClConfig.java`
  - Add disabled-by-default chunk pipeline config.
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java`
  - Print chunk pipeline stats.
- Modify after locating exact hook: `common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinNoiseBasedChunkGenerator$fast_do_fill.java`
  - Add a guarded call that cancels vanilla fill only after the chunk runtime commits successfully.
- Modify if needed: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`
  - Add chunk-output kernels only after Java-side request/layout tests pass.

---

## Task 1: Data Model, Config, and Stats

**Files:**
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRequest.java`
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkOutputLayout.java`
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkResult.java`
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkStats.java`
- Create: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClConfig.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java`

- [ ] **Step 1: Write failing request/layout/result tests**

Add `DfcOpenClChunkPipelineTest` with tests equivalent to:

```java
package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class DfcOpenClChunkPipelineTest {
    @Test
    void singleChunkLayoutUsesBlockResolutionHeight() {
        DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
                3, -2, -64, 384, 4, 8, 1 << 24, false);
        DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);

        assertEquals(1, request.chunkCount());
        assertEquals(98_304, layout.valuesPerChunk());
        assertEquals(98_304, layout.totalValues());
        assertEquals(786_432, layout.densityOutputBytes());
        assertEquals(393_216, layout.packedBlockOutputBytes());
        assertEquals(16 * 16 * 384, layout.valuesPerChunk());
    }

    @Test
    void blockCoordinatesRoundTrip() {
        DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
                3, -2, -64, 384, 4, 8, 1 << 24, false);
        DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);

        int index = layout.index(0, 15, 383, 7);
        assertEquals(3 * 16 + 15, layout.blockX(index));
        assertEquals(-64 + 383, layout.blockY(index));
        assertEquals(-2 * 16 + 7, layout.blockZ(index));
        assertEquals(0, layout.chunkIndex(index));
        assertEquals(15, layout.localX(index));
        assertEquals(7, layout.localZ(index));
    }

    @Test
    void packedBlockResultExposesFlags() {
        int postProcess = DfcOpenClChunkResult.POST_PROCESS_FLAG;
        DfcOpenClChunkResult result = DfcOpenClChunkResult.packedBlocks(new int[] {
                42,
                postProcess | 99
        });

        assertEquals(42, result.blockStateId(0));
        assertFalse(result.requiresPostProcessing(0));
        assertEquals(99, result.blockStateId(1));
        assertTrue(result.requiresPostProcessing(1));
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest
```

Expected: compilation fails because the chunk classes do not exist.

- [ ] **Step 3: Implement minimal data classes**

Create `DfcOpenClChunkRequest`:

```java
package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

public record DfcOpenClChunkRequest(
        int firstChunkX,
        int firstChunkZ,
        int chunkCountX,
        int chunkCountZ,
        int minBlockY,
        int height,
        int cellWidth,
        int cellHeight,
        int maxOutputBytes,
        boolean validationEnabled) {
    public static DfcOpenClChunkRequest singleChunk(
            int chunkX,
            int chunkZ,
            int minBlockY,
            int height,
            int cellWidth,
            int cellHeight,
            int maxOutputBytes,
            boolean validationEnabled) {
        return new DfcOpenClChunkRequest(chunkX, chunkZ, 1, 1, minBlockY, height,
                cellWidth, cellHeight, maxOutputBytes, validationEnabled);
    }

    public int chunkCount() {
        return chunkCountX * chunkCountZ;
    }

    public boolean validShape() {
        return chunkCountX > 0 && chunkCountZ > 0
                && height > 0 && height % 16 == 0
                && cellWidth > 0 && cellHeight > 0
                && maxOutputBytes >= 0;
    }
}
```

Create `DfcOpenClChunkOutputLayout` with Java-order block output:

```java
package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

public final class DfcOpenClChunkOutputLayout {
    private static final int CHUNK_WIDTH = 16;
    private static final int BLOCKS_PER_LAYER = CHUNK_WIDTH * CHUNK_WIDTH;
    private final DfcOpenClChunkRequest request;
    private final int valuesPerChunk;
    private final int totalValues;

    private DfcOpenClChunkOutputLayout(DfcOpenClChunkRequest request) {
        this.request = request;
        this.valuesPerChunk = BLOCKS_PER_LAYER * request.height();
        this.totalValues = valuesPerChunk * request.chunkCount();
    }

    public static DfcOpenClChunkOutputLayout forRequest(DfcOpenClChunkRequest request) {
        if (request == null || !request.validShape()) {
            throw new IllegalArgumentException("invalid chunk request");
        }
        return new DfcOpenClChunkOutputLayout(request);
    }

    public int valuesPerChunk() {
        return valuesPerChunk;
    }

    public int totalValues() {
        return totalValues;
    }

    public int densityOutputBytes() {
        return Math.multiplyExact(totalValues, Double.BYTES);
    }

    public int packedBlockOutputBytes() {
        return Math.multiplyExact(totalValues, Integer.BYTES);
    }

    public int index(int chunkIndex, int localX, int localY, int localZ) {
        return chunkIndex * valuesPerChunk + localY * BLOCKS_PER_LAYER + localZ * CHUNK_WIDTH + localX;
    }

    public int chunkIndex(int index) {
        return index / valuesPerChunk;
    }

    public int localX(int index) {
        return index % CHUNK_WIDTH;
    }

    public int localZ(int index) {
        return (index / CHUNK_WIDTH) % CHUNK_WIDTH;
    }

    public int localY(int index) {
        return (index % valuesPerChunk) / BLOCKS_PER_LAYER;
    }

    public int blockX(int index) {
        int chunkOffsetX = chunkIndex(index) % request.chunkCountX();
        return (request.firstChunkX() + chunkOffsetX) * CHUNK_WIDTH + localX(index);
    }

    public int blockY(int index) {
        return request.minBlockY() + localY(index);
    }

    public int blockZ(int index) {
        int chunkOffsetZ = chunkIndex(index) / request.chunkCountX();
        return (request.firstChunkZ() + chunkOffsetZ) * CHUNK_WIDTH + localZ(index);
    }
}
```

Create `DfcOpenClChunkResult`:

```java
package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import java.util.Objects;

public final class DfcOpenClChunkResult {
    public static final int POST_PROCESS_FLAG = 1 << 31;
    private static final int BLOCK_STATE_MASK = ~POST_PROCESS_FLAG;

    private final double[] densities;
    private final int[] packedBlocks;

    private DfcOpenClChunkResult(double[] densities, int[] packedBlocks) {
        this.densities = densities;
        this.packedBlocks = packedBlocks;
    }

    public static DfcOpenClChunkResult densities(double[] densities) {
        return new DfcOpenClChunkResult(Objects.requireNonNull(densities, "densities"), null);
    }

    public static DfcOpenClChunkResult packedBlocks(int[] packedBlocks) {
        return new DfcOpenClChunkResult(null, Objects.requireNonNull(packedBlocks, "packedBlocks"));
    }

    public boolean hasDensities() {
        return densities != null;
    }

    public boolean hasPackedBlocks() {
        return packedBlocks != null;
    }

    public double density(int index) {
        return densities[index];
    }

    public int packedBlock(int index) {
        return packedBlocks[index];
    }

    public int blockStateId(int index) {
        return packedBlocks[index] & BLOCK_STATE_MASK;
    }

    public boolean requiresPostProcessing(int index) {
        return (packedBlocks[index] & POST_PROCESS_FLAG) != 0;
    }
}
```

- [ ] **Step 4: Add disabled-by-default config**

Add methods to `DfcOpenClConfig`:

```java
public static boolean chunkNoiseEnabled() {
    return enabled() && boolProperty("dfc.opencl.chunkNoise", false);
}

public static boolean chunkNoiseValidationEnabled() {
    return boolProperty("dfc.opencl.chunkNoiseValidation", true);
}

public static int chunkNoiseMaxOutputBytes() {
    return intProperty("dfc.opencl.chunkNoiseMaxOutputBytes", 64 << 20, 0, 1 << 30);
}
```

- [ ] **Step 5: Add chunk stats snapshot and stats command output**

Implement `DfcOpenClChunkStats` with `LongAdder` counters for `calls`, `skipped`, `attempts`, `succeeded`, `failed`, `chunks`, `batches`, `outputBytes`, `totalNanos`, `maxNanos`, and `AtomicReference<String>` values for `lastSkip` and `lastFailure`.

Add a `DensityFunctionCompiler` stats line formatted as:

```text
DFC OpenCL chunk noise: calls=..., skipped=..., attempts=..., succeeded=..., failed=..., chunks=..., batches=..., outputBytes=..., totalMs=..., avgChunkMs=..., avgBlockNs=..., maxMs=..., lastSkip=..., lastFailure=...
```

- [ ] **Step 6: Verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest
```

Expected: tests pass and existing OpenCL command stats tests accept the new stats line.

- [ ] **Step 7: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClConfig.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java
git commit -m "Add OpenCL chunk pipeline data model"
```

## Task 2: Fail-Soft Runtime Entry

**Files:**
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkStats.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`

- [ ] **Step 1: Write failing runtime preflight tests**

Add tests:

```java
@Test
void runtimeRejectsDisabledConfigWithoutThrowing() {
    DfcOpenClChunkRuntime runtime = new DfcOpenClChunkRuntime();
    DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
            0, 0, -64, 384, 4, 8, 1 << 24, false);

    assertFalse(runtime.tryEvaluateDensityPrototype(request).present());
    assertEquals("disabled", DfcOpenClChunkStats.snapshot().lastSkip());
}

@Test
void runtimeRejectsOversizedOutputBeforeOpenCl() {
    DfcOpenClChunkRuntime runtime = new DfcOpenClChunkRuntime();
    DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
            0, 0, -64, 384, 4, 8, 1, false);

    DfcOpenClChunkRuntime.Attempt attempt = runtime.preflight(request, DfcOpenClChunkRuntime.OutputMode.DENSITY);
    assertFalse(attempt.allowed());
    assertEquals("memory", attempt.reason());
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest
```

Expected: compilation fails because `DfcOpenClChunkRuntime` does not exist.

- [ ] **Step 3: Implement fail-soft shell**

Create `DfcOpenClChunkRuntime`:

```java
package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;

public final class DfcOpenClChunkRuntime {
    public enum OutputMode {
        DENSITY,
        PACKED_BLOCKS
    }

    public record Attempt(boolean allowed, String reason) {
        public static Attempt allowed() {
            return new Attempt(true, "ok");
        }

        public static Attempt rejected(String reason) {
            return new Attempt(false, reason);
        }
    }

    public record Result(boolean present, DfcOpenClChunkResult output, String reason) {
        public static Result empty(String reason) {
            return new Result(false, null, reason);
        }
    }

    public Attempt preflight(DfcOpenClChunkRequest request, OutputMode mode) {
        if (!DfcOpenClConfig.chunkNoiseEnabled()) {
            return Attempt.rejected("disabled");
        }
        if (request == null || !request.validShape()) {
            return Attempt.rejected("shape");
        }
        DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);
        int bytes = mode == OutputMode.DENSITY ? layout.densityOutputBytes() : layout.packedBlockOutputBytes();
        if (bytes > request.maxOutputBytes()) {
            return Attempt.rejected("memory");
        }
        return Attempt.allowed();
    }

    public Result tryEvaluateDensityPrototype(DfcOpenClChunkRequest request) {
        DfcOpenClChunkStats.recordCall();
        Attempt attempt = preflight(request, OutputMode.DENSITY);
        if (!attempt.allowed()) {
            DfcOpenClChunkStats.recordSkip(attempt.reason());
            return Result.empty(attempt.reason());
        }
        DfcOpenClChunkStats.recordSkip("no_plan");
        return Result.empty("no_plan");
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the chunk pipeline test class. Expected: runtime shell tests pass.

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkStats.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java
git commit -m "Add fail-soft OpenCL chunk runtime shell"
```

## Task 3: Safe Generator Hook Discovery

**Files:**
- Inspect: `common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin/MixinNoiseBasedChunkGenerator$fast_do_fill.java`
- Inspect: `common/src/main/resources/*.mixins.json`
- Modify after discovery: the exact mixin file that owns terrain fill cancellation.

- [ ] **Step 1: Locate the terrain fill hook and mapped signatures**

Run:

```powershell
rg "NoiseBasedChunkGenerator|doFill|fillFromNoise|iterateNoiseColumn|ChunkAccess|NoiseChunk" common/src/main/java common/src/main/resources -n
```

Record the exact method and injection point in the implementation commit message. Do not guess the hook signature from memory.

- [ ] **Step 2: Add a guarded no-op hook**

Add a call shaped like this at the earliest point where the target `ChunkAccess`, generator settings, and noise context are available, but keep it no-op because `tryFillSingleChunk` does not exist yet:

```java
if (DfcOpenClConfig.chunkNoiseEnabled()
        && DfcOpenClChunkRuntime.global().tryFillSingleChunk(/* exact discovered args */)) {
    cir.setReturnValue(CompletableFuture.completedFuture(chunkAccess));
    return;
}
```

If the target method is not cancellable, inject at a cancellable head or create a separate mixin rather than partially mutating vanilla state.

- [ ] **Step 3: Verify disabled default is behavior-neutral**

Run:

```powershell
.\gradlew.bat build -x test
```

Expected: build succeeds. With no JVM property set, `dfc.opencl.chunkNoise` is false and the hook never cancels vanilla.

- [ ] **Step 4: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/mixins/common_mixin common/src/main/resources
git commit -m "Wire disabled OpenCL chunk noise hook"
```

## Task 4: Single-Chunk Density Prototype Dispatch

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java`
- Modify: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`

- [ ] **Step 1: Add coordinate/layout tests for full chunk density**

Test that the chunk output layout maps GPU index `layout.index(0, x, y, z)` to absolute coordinates `(chunkX * 16 + x, minY + y, chunkZ * 16 + z)` for multiple corners:

```java
int[][] points = {
        {0, 0, 0},
        {15, 0, 15},
        {0, 383, 0},
        {15, 383, 15}
};
```

- [ ] **Step 2: Expose a narrow generated finalDensity helper**

Add a package-private helper in `DfcOpenClRuntime` that evaluates an already available finalDensity generated plan over a chunk layout and returns `double[]`. Keep the public API in `DfcOpenClChunkRuntime`; do not expose a second production entry point from `DfcOpenClRuntime`.

The helper must reject unsupported plans with these strings:

```text
no_plan
no_waves
flatcache_unbound
aquifer
opencl
```

- [ ] **Step 3: Add OpenCL coordinate mapping**

In `dfc_slab_vm.cl`, add a chunk-output layout branch that computes:

```c
int local_x = element % 16;
int local_z = (element / 16) % 16;
int local_y = (element / 256) % height;
int chunk_index = element / (16 * 16 * height);
int chunk_x = first_chunk_x + (chunk_index % chunk_count_x);
int chunk_z = first_chunk_z + (chunk_index / chunk_count_x);
int block_x = chunk_x * 16 + local_x;
int block_y = min_block_y + local_y;
int block_z = chunk_z * 16 + local_z;
```

- [ ] **Step 4: Route density prototype through the helper**

Update `DfcOpenClChunkRuntime.tryEvaluateDensityPrototype`:

- record attempt before dispatch,
- call the helper,
- record success with chunk count, output bytes, and elapsed time,
- record failure without throwing to the generator hook.

- [ ] **Step 5: Verify diagnostics still pass**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest
.\gradlew.bat build -x test
```

Expected: tests/build pass; existing diagnostic finalDensity commands still compile at runtime.

- [ ] **Step 6: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/DfcOpenClRuntime.java common/src/main/resources/dfc_opencl/dfc_slab_vm.cl common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java
git commit -m "Evaluate full chunk density with OpenCL"
```

## Task 5: Direct Packed Block Output and Writer

**Files:**
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkBlockWriter.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
- Modify: `common/src/main/resources/dfc_opencl/dfc_slab_vm.cl`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`
- Inspect/reuse: `common/src/main/java/dev/sixik/generator_accelerator/common/worldgen/workspace/GAChunkBlockIo.java`
- Inspect/reuse: `common/src/main/java/dev/sixik/generator_accelerator/common/flat_block_structure/LevelChunkSection$FlatBlockArray.java`
- Inspect/reuse: `common/src/main/java/dev/sixik/generator_accelerator/api/structures/FastBlockStateCache.java`

- [ ] **Step 1: Write fake writer tests before touching real chunks**

Create a test fake that records writes as `(x, y, z, blockStateId, postProcess)`. Assert:

- output index `layout.index(0, 0, 0, 0)` writes to local `(0, minY, 0)`,
- output index `layout.index(0, 15, 383, 15)` writes to local `(15, minY + 383, 15)`,
- a packed value with `POST_PROCESS_FLAG` records `postProcess=true`,
- an output length mismatch returns `false` and records no writes.

- [ ] **Step 2: Implement writer validation**

`DfcOpenClChunkBlockWriter` must validate all destination sections and output length before the first mutation:

```java
public boolean canWrite(DfcOpenClChunkRequest request, DfcOpenClChunkOutputLayout layout,
        DfcOpenClChunkResult result) {
    return request != null
            && layout != null
            && result != null
            && result.hasPackedBlocks()
            && layout.totalValues() == result.packedLength();
}
```

Add `packedLength()` to `DfcOpenClChunkResult`.

- [ ] **Step 3: Add direct block OpenCL output mode**

Add a kernel mode that writes `int` block output instead of `double` density output. The first direct mode may handle the base terrain decision only; if aquifer or fluid logic is not fully represented, the runtime must reject with `aquifer` rather than silently producing wrong blocks.

- [ ] **Step 4: Connect runtime to writer**

Add:

```java
public boolean tryFillSingleChunk(/* exact generator hook args */) {
    DfcOpenClChunkStats.recordCall();
    // preflight, unsupported Blender check, direct output dispatch, validation, writeback
    return false;
}
```

Return `true` only after `DfcOpenClChunkBlockWriter` commits successfully.

- [ ] **Step 5: Verify tests and manual no-blending generation**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest
.\gradlew.bat build -x test
```

Manual run with:

```text
-Ddfc.opencl.enabled=true
-Ddfc.opencl.chunkNoise=true
-Ddfc.opencl.chunkNoiseValidation=true
```

Expected: chunk stats show calls and either safe skips or successful no-blending chunk writes. Any mismatch must skip before commit.

- [ ] **Step 6: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/main/resources/dfc_opencl/dfc_slab_vm.cl common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java
git commit -m "Write OpenCL chunk block output"
```

## Task 6: Persistent Chunk Runtime and Buffer Cache

**Files:**
- Create: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkBufferCache.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkStats.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`

- [ ] **Step 1: Write buffer cache sizing tests**

Test that requesting buffers of the same rounded size reuses one entry, requesting a larger size creates or grows an entry, and `close()` releases all tracked buffers.

- [ ] **Step 2: Implement owner-thread buffer cache**

`DfcOpenClChunkBufferCache` should:

- round byte sizes to a small set of buckets,
- store buffers by OpenCL context/device identity,
- clear buffers on runtime reset or device loss,
- never share mutable buffers across concurrent command queues unless the queue ownership is explicit.

- [ ] **Step 3: Add timing stats**

Extend chunk stats with:

```text
setupNanos
compileNanos
uploadNanos
kernelNanos
readbackNanos
validateNanos
writebackNanos
allocations
cacheHits
```

- [ ] **Step 4: Verify warmup behavior**

Run a manual generation session. Expected after warmup:

- `cacheHits` increases,
- allocation count grows slowly or stops,
- `compileMs` is near zero for already-seen plan/layout combinations.

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java
git commit -m "Cache OpenCL chunk buffers"
```

## Task 7: Aligned Multi-Chunk Output Layout

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRequest.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkOutputLayout.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkRuntime.java`
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkBlockWriter.java`
- Modify: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java`

- [ ] **Step 1: Write `2x2` layout tests**

Add tests:

```java
DfcOpenClChunkRequest request = DfcOpenClChunkRequest.alignedBatch(
        8, 12, 2, 2, -64, 384, 4, 8, 64 << 20, true);
DfcOpenClChunkOutputLayout layout = DfcOpenClChunkOutputLayout.forRequest(request);

assertEquals(4, request.chunkCount());
assertEquals(393_216 * 4, layout.packedBlockOutputBytes());
assertEquals(0, layout.chunkIndex(layout.index(0, 0, 0, 0)));
assertEquals(3, layout.chunkIndex(layout.index(3, 15, 383, 15)));
assertEquals((8 + 1) * 16 + 15, layout.blockX(layout.index(3, 15, 383, 15)));
assertEquals((12 + 1) * 16 + 15, layout.blockZ(layout.index(3, 15, 383, 15)));
```

- [ ] **Step 2: Implement aligned request factory**

Add:

```java
public static DfcOpenClChunkRequest alignedBatch(
        int firstChunkX,
        int firstChunkZ,
        int chunkCountX,
        int chunkCountZ,
        int minBlockY,
        int height,
        int cellWidth,
        int cellHeight,
        int maxOutputBytes,
        boolean validationEnabled)
```

Reject non-positive sizes and sizes other than `1`, `2`, or `4` in preflight with `shape`.

- [ ] **Step 3: Add writer split tests**

Use fake chunks/writers to assert each chunk index writes only to its own destination.

- [ ] **Step 4: Add runtime batch preflight**

Add `tryFillAlignedBatch(...)` but return `false` until the scheduler/hook can prove lifecycle ownership. Record `lifecycle` for unsafe or partial batches.

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/opencl/chunk/DfcOpenClChunkPipelineTest.java
git commit -m "Add aligned OpenCL chunk batch layout"
```

## Task 8: Diagnostics and Manual Commands

**Files:**
- Modify: `common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java`
- Modify command tests: `common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompilerOpenClCommandTest.java`

- [ ] **Step 1: Make stats actionable**

Ensure `/dfc opencl stats` includes:

```text
DFC OpenCL chunk noise: calls=..., skipped=..., attempts=..., succeeded=..., failed=..., chunks=..., batches=..., outputBytes=..., totalMs=..., avgChunkMs=..., avgBlockNs=..., maxMs=..., lastSkip=..., lastFailure=...
```

- [ ] **Step 2: Add command tests**

Assert command output contains:

```java
assertTrue(message.contains("DFC OpenCL chunk noise:"));
assertTrue(message.contains("lastSkip="));
assertTrue(message.contains("lastFailure="));
```

- [ ] **Step 3: Add optional check/bench commands after runtime is callable**

Add commands only if they can run without a live world or if they are clearly world-required in help text:

```text
/dfc opencl chunknoise check
/dfc opencl chunknoise bench
/dfc opencl chunknoise trace
```

- [ ] **Step 4: Verify**

Run:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest
.\gradlew.bat build -x test
```

- [ ] **Step 5: Commit**

```powershell
git add common/src/main/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompiler.java common/src/test/java/dev/sixik/generator_accelerator/common/density/compiler/DensityFunctionCompilerOpenClCommandTest.java
git commit -m "Expose OpenCL chunk noise diagnostics"
```

## Stop Conditions

Stop and reassess before continuing if any of these happens:

- Direct block output mismatches CPU/vanilla for no-blending chunks.
- OpenCL direct output requires a full chunk-sized buffer for most intermediate DFC slots and exceeds the memory cap.
- Writeback time dominates kernel time after packed block output.
- The generator hook cannot prove it owns the target chunk sections before writing.
- Blender or FlatCache data must be retained across `NoiseChunk` lifetimes to make the path work.
- A failure can occur after partial writes without a clear rollback or "validate before mutation" guarantee.

## Verification Bundle

After each implementation task:

```powershell
git diff --check
```

Before enabling runtime testing:

```powershell
.\gradlew.bat test --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkPipelineTest --tests dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClGeneratedNoiseSourceTest --tests dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompilerOpenClCommandTest
.\gradlew.bat build -x test
git diff --check
```

Manual runtime JVM properties for guarded testing:

```text
-Ddfc.opencl.enabled=true
-Ddfc.opencl.chunkNoise=true
-Ddfc.opencl.chunkNoiseValidation=true
```

Manual success signals:

- `/dfc opencl stats` shows `chunk noise attempts > 0` and `succeeded > 0` for no-blending chunks.
- Existing finalDensity all-waves output check still passes.
- Generation is visually correct at chunk borders and cave/fluid transitions.
- Wall-clock generation is faster than the current Y/Z slice path before attempting `2x2` or `4x4`.
