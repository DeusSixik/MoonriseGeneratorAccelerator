package dev.sixik.generator_accelerator.common.noise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GANoiseFillMetricsTest {

    @AfterEach
    void tearDown() {
        GANoiseFillMetrics.setEnabled(Boolean.getBoolean("ga.noiseFill.metrics"));
        GANoiseFillMetrics.reset();
    }

    @Test
    void countersStayColdWhenDisabled() {
        GANoiseFillMetrics.setEnabled(false);
        GANoiseFillMetrics.reset();

        GANoiseFillMetrics.increment(GANoiseFillMetrics.DO_FILL_CHUNKS);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_ATTEMPTS, 7L);

        assertEquals(0L, GANoiseFillMetrics.get(GANoiseFillMetrics.DO_FILL_CHUNKS));
        assertEquals(0L, GANoiseFillMetrics.get(GANoiseFillMetrics.DIRECT_ATTEMPTS));
    }

    @Test
    void snapshotReportsDirectHitRateAndNewOreCounter() {
        GANoiseFillMetrics.setEnabled(true);
        GANoiseFillMetrics.reset();

        GANoiseFillMetrics.increment(GANoiseFillMetrics.DO_FILL_CHUNKS);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DO_FILL_NANOS, 2_000_000L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_ATTEMPTS, 4L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_HITS, 3L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.POSITIVE_DENSITY_ORE_FAST_SAMPLES, 2L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_NEGATIVE_GLOBAL_FLUID_FAST_SAMPLES, 1L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_CELL_FAST_CELLS, 1L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_CELL_FAST_BLOCKS, 128L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_CELL_BULK_WRITES, 1L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_CLASSIFIER_HITS, 2L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_CLASSIFIER_SCAN_FALLBACKS, 1L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_SUMMARY_INTEGRATED, 3L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_SUMMARY_SCAN_FALLBACKS, 4L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_SUMMARY_FAST_FAILURES, 5L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_SURFACE_FAST_CELLS, 6L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_SURFACE_FAST_BLOCKS, 768L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.FUSED_TERRAIN_CHUNKS, 7L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_CELL_AVAILABLE_CHUNKS, 8L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_CELL_MISSING_CHUNKS, 9L);

        GANoiseFillMetrics.Snapshot snapshot = GANoiseFillMetrics.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(1L, snapshot.chunks());
        assertEquals(2L, snapshot.totalMillis());
        assertEquals(4L, snapshot.directAttempts());
        assertEquals(3L, snapshot.directHits());
        assertEquals(0.75D, snapshot.directHitRate(), 0.0D);
        assertEquals(2L, snapshot.positiveDensityOreFastSamples());
        assertEquals(0L, snapshot.directSolidCellFastCells());
        assertEquals(0L, snapshot.directSolidCellFastBlocks());
        assertEquals(1L, snapshot.directNegativeGlobalFluidFastSamples());
        assertEquals(1L, snapshot.directHighAirCellFastCells());
        assertEquals(128L, snapshot.directHighAirCellFastBlocks());
        assertEquals(1L, snapshot.directSolidCellBulkWrites());
        assertEquals(2L, snapshot.densityClassifierHits());
        assertEquals(1L, snapshot.densityClassifierScanFallbacks());
        assertEquals(3L, snapshot.densitySummaryIntegrated());
        assertEquals(4L, snapshot.densitySummaryScanFallbacks());
        assertEquals(5L, snapshot.densitySummaryFastFailures());
        assertEquals(6L, snapshot.directHighAirSurfaceFastCells());
        assertEquals(768L, snapshot.directHighAirSurfaceFastBlocks());
        assertEquals(7L, snapshot.fusedTerrainChunks());
        assertEquals(8L, snapshot.directCellAvailableChunks());
        assertEquals(9L, snapshot.directCellMissingChunks());
        assertTrue(snapshot.summary().contains("positiveDensityOreFastSamples=2"));
        assertTrue(snapshot.summary().contains("negativeGlobalFluidFastSamples=1"));
        assertTrue(snapshot.summary().contains("highAirCellFastBlocks=128"));
        assertTrue(snapshot.summary().contains("densityClassifierHits=2"));
        assertTrue(snapshot.summary().contains("densitySummaryIntegrated=3"));
        assertTrue(snapshot.summary().contains("highAirSurfaceFastBlocks=768"));
        assertTrue(snapshot.summary().contains("directCellAvailableChunks=8"));
    }
}
