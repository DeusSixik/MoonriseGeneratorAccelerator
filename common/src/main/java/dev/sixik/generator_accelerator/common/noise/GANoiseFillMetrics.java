package dev.sixik.generator_accelerator.common.noise;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counters for {@code NoiseBasedChunkGenerator#doFill}. The hot loop keeps
 * per-chunk primitive counters and flushes them here only when metrics are on.
 */
public final class GANoiseFillMetrics {
    public static volatile boolean ENABLED = Boolean.getBoolean("ga.noiseFill.metrics");

    public static final int DO_FILL_CHUNKS = 0;
    public static final int DO_FILL_NANOS = 1;
    public static final int SLOW_SAMPLES = 2;
    public static final int UPDATE_Y_CALLS = 3;
    public static final int UPDATE_X_CALLS = 4;
    public static final int UPDATE_Z_CALLS = 5;
    public static final int SELECT_CELL_CALLS = 6;
    public static final int SELECT_CELL_NANOS = 7;
    public static final int CELL_CACHE_EAGER_FILLS = 8;
    public static final int AQUIFER_SCHEDULE_CHECKS = 9;
    public static final int NON_AIR_SAMPLES = 10;
    public static final int SECTION_LOCAL_RAW_COMMITS = 11;
    public static final int SECTION_LOCAL_RAW_WRITES = 12;
    public static final int SECTION_LOCAL_RAW_FALLBACKS = 13;

    public static final int COUNTER_COUNT = 14;

    private static final String[] NAMES = {
            "doFill.chunks",
            "doFill.nanos",
            "slow.samples",
            "interpolation.updateYCalls",
            "interpolation.updateXCalls",
            "interpolation.updateZCalls",
            "cell.selectCalls",
            "cell.selectNanos",
            "cellCache.eagerFills",
            "aquifer.scheduleChecks",
            "samples.nonAir",
            "sectionLocalRaw.commits",
            "sectionLocalRaw.writes",
            "sectionLocalRaw.fallbacks"
    };

    private static final AtomicLongArray COUNTERS = new AtomicLongArray(COUNTER_COUNT);

    private GANoiseFillMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void addElapsed(int counter, long startNanos) {
        if (ENABLED) {
            add(counter, System.nanoTime() - startNanos);
        }
    }

    public static void increment(int counter) {
        if (ENABLED) {
            COUNTERS.incrementAndGet(counter);
        }
    }

    public static void add(int counter, long amount) {
        if (ENABLED && amount != 0L) {
            COUNTERS.addAndGet(counter, amount);
        }
    }

    public static long get(int counter) {
        return COUNTERS.get(counter);
    }

    public static String name(int counter) {
        return NAMES[counter];
    }

    public static void copyTo(long[] out) {
        int limit = Math.min(out.length, COUNTER_COUNT);
        for (int i = 0; i < limit; i++) {
            out[i] = COUNTERS.get(i);
        }
    }

    public static void reset() {
        for (int i = 0; i < COUNTER_COUNT; i++) {
            COUNTERS.set(i, 0L);
        }
    }

    public static Snapshot snapshot() {
        long chunks = get(DO_FILL_CHUNKS);
        long nanos = get(DO_FILL_NANOS);
        return new Snapshot(
                ENABLED,
                chunks,
                millis(nanos),
                chunks == 0L ? 0.0D : (double) nanos / (double) chunks,
                get(SLOW_SAMPLES),
                get(UPDATE_Y_CALLS),
                get(UPDATE_X_CALLS),
                get(UPDATE_Z_CALLS),
                get(SELECT_CELL_CALLS),
                millis(get(SELECT_CELL_NANOS)),
                get(CELL_CACHE_EAGER_FILLS),
                get(AQUIFER_SCHEDULE_CHECKS),
                get(NON_AIR_SAMPLES),
                get(SECTION_LOCAL_RAW_COMMITS),
                get(SECTION_LOCAL_RAW_WRITES),
                get(SECTION_LOCAL_RAW_FALLBACKS),
                summary()
        );
    }

    public static String summary() {
        return "NoiseFill metrics: chunks=" + get(DO_FILL_CHUNKS)
                + ", totalMs=" + millis(get(DO_FILL_NANOS))
                + ", slowSamples=" + get(SLOW_SAMPLES)
                + ", updateY=" + get(UPDATE_Y_CALLS)
                + ", updateX=" + get(UPDATE_X_CALLS)
                + ", updateZ=" + get(UPDATE_Z_CALLS)
                + ", selectCellCalls=" + get(SELECT_CELL_CALLS)
                + ", selectCellMs=" + millis(get(SELECT_CELL_NANOS))
                + ", eagerCellFills=" + get(CELL_CACHE_EAGER_FILLS)
                + ", aquiferScheduleChecks=" + get(AQUIFER_SCHEDULE_CHECKS)
                + ", nonAirSamples=" + get(NON_AIR_SAMPLES)
                + ", sectionLocalRawCommits=" + get(SECTION_LOCAL_RAW_COMMITS)
                + ", sectionLocalRawWrites=" + get(SECTION_LOCAL_RAW_WRITES)
                + ", sectionLocalRawFallbacks=" + get(SECTION_LOCAL_RAW_FALLBACKS);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    public record Snapshot(
            boolean enabled,
            long chunks,
            long totalMillis,
            double avgNanosPerChunk,
            long slowSamples,
            long updateYCalls,
            long updateXCalls,
            long updateZCalls,
            long selectCellCalls,
            long selectCellMillis,
            long eagerCellFills,
            long aquiferScheduleChecks,
            long nonAirSamples,
            long sectionLocalRawCommits,
            long sectionLocalRawWrites,
            long sectionLocalRawFallbacks,
            String summary
    ) {
    }
}
