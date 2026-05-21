package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void statsSnapshotRecordsSkipAttemptAndSuccess() {
        DfcOpenClChunkStats.reset();

        DfcOpenClChunkStats.recordCall();
        DfcOpenClChunkStats.recordSkip("disabled");
        DfcOpenClChunkStats.recordAttempt(4, 1024);
        DfcOpenClChunkStats.recordSuccess(4, 1024, 2_000_000L);

        DfcOpenClChunkStats.Snapshot snapshot = DfcOpenClChunkStats.snapshot();
        assertEquals(1, snapshot.calls());
        assertEquals(1, snapshot.skipped());
        assertEquals(1, snapshot.attempts());
        assertEquals(1, snapshot.succeeded());
        assertEquals(0, snapshot.failed());
        assertEquals(4, snapshot.chunks());
        assertEquals(1, snapshot.batches());
        assertEquals(1024, snapshot.outputBytes());
        assertEquals(2_000_000L, snapshot.totalNanos());
        assertEquals(2_000_000L, snapshot.maxNanos());
        assertEquals("disabled", snapshot.lastSkip());
    }

    @Test
    void invalidRequestShapeIsRejected() {
        DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
                0, 0, -64, 383, 4, 8, 1 << 24, false);

        assertFalse(request.validShape());
    }

    @Test
    void runtimeRejectsDisabledConfigWithoutThrowing() {
        withOpenClProperties("false", "false", () -> {
            DfcOpenClChunkStats.reset();
            DfcOpenClChunkRuntime runtime = new DfcOpenClChunkRuntime();
            DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
                    0, 0, -64, 384, 4, 8, 1 << 24, false);

            assertFalse(runtime.tryEvaluateDensityPrototype(request).present());
            assertEquals("disabled", DfcOpenClChunkStats.snapshot().lastSkip());
        });
    }

    @Test
    void runtimeRejectsOversizedOutputBeforeOpenCl() {
        withOpenClProperties("true", "true", () -> {
            DfcOpenClChunkRuntime runtime = new DfcOpenClChunkRuntime();
            DfcOpenClChunkRequest request = DfcOpenClChunkRequest.singleChunk(
                    0, 0, -64, 384, 4, 8, 1, false);

            DfcOpenClChunkRuntime.Attempt attempt =
                    runtime.preflight(request, DfcOpenClChunkRuntime.OutputMode.DENSITY);
            assertFalse(attempt.allowed());
            assertEquals("memory", attempt.reason());
        });
    }

    private static void withOpenClProperties(String enabled, String chunkNoise, Runnable action) {
        String oldEnabled = System.getProperty("dfc.opencl.enabled");
        String oldChunkNoise = System.getProperty("dfc.opencl.chunkNoise");
        System.setProperty("dfc.opencl.enabled", enabled);
        System.setProperty("dfc.opencl.chunkNoise", chunkNoise);
        try {
            action.run();
        } finally {
            restoreProperty("dfc.opencl.enabled", oldEnabled);
            restoreProperty("dfc.opencl.chunkNoise", oldChunkNoise);
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
