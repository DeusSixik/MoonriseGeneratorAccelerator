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
        GANoiseFillMetrics.add(GANoiseFillMetrics.SLOW_SAMPLES, 7L);

        assertEquals(0L, GANoiseFillMetrics.get(GANoiseFillMetrics.DO_FILL_CHUNKS));
        assertEquals(0L, GANoiseFillMetrics.get(GANoiseFillMetrics.SLOW_SAMPLES));
    }

    @Test
    void snapshotReportsLiveCounters() {
        GANoiseFillMetrics.setEnabled(true);
        GANoiseFillMetrics.reset();

        GANoiseFillMetrics.increment(GANoiseFillMetrics.DO_FILL_CHUNKS);
        GANoiseFillMetrics.add(GANoiseFillMetrics.DO_FILL_NANOS, 2_000_000L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.SLOW_SAMPLES, 4L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Y_CALLS, 10L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_X_CALLS, 11L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Z_CALLS, 12L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_CALLS, 13L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_NANOS, 3_000_000L);
        GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_CACHE_EAGER_FILLS, 2L);

        GANoiseFillMetrics.Snapshot snapshot = GANoiseFillMetrics.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(1L, snapshot.chunks());
        assertEquals(2L, snapshot.totalMillis());
        assertEquals(4L, snapshot.slowSamples());
        assertEquals(10L, snapshot.updateYCalls());
        assertEquals(11L, snapshot.updateXCalls());
        assertEquals(12L, snapshot.updateZCalls());
        assertEquals(13L, snapshot.selectCellCalls());
        assertEquals(3L, snapshot.selectCellMillis());
        assertEquals(2L, snapshot.eagerCellFills());
        assertTrue(snapshot.summary().contains("slowSamples=4"));
        assertTrue(snapshot.summary().contains("selectCellCalls=13"));
        assertTrue(snapshot.summary().contains("eagerCellFills=2"));
    }
}
