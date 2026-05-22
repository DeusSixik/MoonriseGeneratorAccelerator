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
    public static final int DIRECT_ELIGIBLE_CHUNKS = 2;
    public static final int DIRECT_ATTEMPTS = 3;
    public static final int DIRECT_SOLID_HITS = 4;
    public static final int DIRECT_AIR_HITS = 5;
    public static final int DIRECT_FALLBACK_UNAVAILABLE = 6;
    public static final int DIRECT_FALLBACK_ORE_VEIN_RANGE = 7;
    public static final int DIRECT_FALLBACK_NON_SOLID = 8;
    public static final int DIRECT_FALLBACK_OOB = 9;
    public static final int DIRECT_FALLBACK_OTHER = 10;
    public static final int SLOW_SAMPLES = 11;
    public static final int FUSED_FALLBACKS_TO_VANILLA = 12;
    public static final int UPDATE_Y_CALLS = 13;
    public static final int UPDATE_X_CALLS = 14;
    public static final int UPDATE_Z_CALLS = 15;
    public static final int UPDATE_Y_SKIPPED_BY_DIRECT = 16;
    public static final int UPDATE_X_SKIPPED_BY_DIRECT = 17;
    public static final int UPDATE_Z_SKIPPED_BY_DIRECT = 18;
    public static final int SELECT_CELL_CALLS = 19;
    public static final int SELECT_CELL_NANOS = 20;
    public static final int CELL_CACHE_EAGER_FILLS = 21;
    public static final int CELL_CACHE_LAZY_FILLS = 22;
    public static final int CELL_CACHE_LAZY_HITS = 23;
    public static final int CELL_CACHE_RECURSION_SKIPS = 24;
    public static final int TERRAIN_CACHE_PREFILLS = 25;
    public static final int POSITIVE_DENSITY_ORE_FAST_SAMPLES = 26;
    public static final int CELL_CACHE_LATE_ARRAY_EPOCHS = 27;
    public static final int DIRECT_SOLID_CELL_FAST_CELLS = 28;
    public static final int DIRECT_SOLID_CELL_FAST_BLOCKS = 29;
    public static final int DIRECT_NEGATIVE_GLOBAL_FLUID_FAST_SAMPLES = 30;
    public static final int DIRECT_HIGH_AIR_CELL_FAST_CELLS = 31;
    public static final int DIRECT_HIGH_AIR_CELL_FAST_BLOCKS = 32;
    public static final int DIRECT_GLOBAL_LAVA_CELL_FAST_CELLS = 33;
    public static final int DIRECT_GLOBAL_LAVA_CELL_FAST_BLOCKS = 34;
    public static final int DIRECT_SOLID_CELL_BULK_WRITES = 35;
    public static final int CELL_DENSITY_CLASSIFIER_HITS = 36;
    public static final int CELL_DENSITY_CLASSIFIER_SCAN_FALLBACKS = 37;
    public static final int CELL_DENSITY_SUMMARY_INTEGRATED = 38;
    public static final int CELL_DENSITY_SUMMARY_SCAN_FALLBACKS = 39;
    public static final int CELL_DENSITY_SUMMARY_FAST_FAILURES = 40;
    public static final int DIRECT_HIGH_AIR_SURFACE_FAST_CELLS = 41;
    public static final int DIRECT_HIGH_AIR_SURFACE_FAST_BLOCKS = 42;
    public static final int FUSED_TERRAIN_CHUNKS = 43;
    public static final int DIRECT_CELL_AVAILABLE_CHUNKS = 44;
    public static final int DIRECT_CELL_MISSING_CHUNKS = 45;

    public static final int COUNTER_COUNT = 46;

    private static final String[] NAMES = {
            "doFill.chunks",
            "doFill.nanos",
            "direct.eligibleChunks",
            "direct.attempts",
            "direct.solidHits",
            "direct.airHits",
            "direct.fallbackUnavailable",
            "direct.fallbackOreVeinRange",
            "direct.fallbackNonSolid",
            "direct.fallbackOutOfBounds",
            "direct.fallbackOther",
            "slow.samples",
            "slow.fusedFallbacksToVanilla",
            "interpolation.updateYCalls",
            "interpolation.updateXCalls",
            "interpolation.updateZCalls",
            "interpolation.updateYSkippedByDirect",
            "interpolation.updateXSkippedByDirect",
            "interpolation.updateZSkippedByDirect",
            "cell.selectCalls",
            "cell.selectNanos",
            "cellCache.eagerFills",
            "cellCache.lazyFills",
            "cellCache.lazyHits",
            "cellCache.recursionSkips",
            "cellCache.terrainPrefills",
            "direct.positiveDensityOreFastSamples",
            "cellCache.lateArrayEpochs",
            "direct.solidCellFastCells",
            "direct.solidCellFastBlocks",
            "direct.negativeGlobalFluidFastSamples",
            "direct.highAirCellFastCells",
            "direct.highAirCellFastBlocks",
            "direct.globalLavaCellFastCells",
            "direct.globalLavaCellFastBlocks",
            "direct.solidCellBulkWrites",
            "cell.densityClassifierHits",
            "cell.densityClassifierScanFallbacks",
            "cell.densitySummaryIntegrated",
            "cell.densitySummaryScanFallbacks",
            "cell.densitySummaryFastFailures",
            "direct.highAirSurfaceFastCells",
            "direct.highAirSurfaceFastBlocks",
            "fusedTerrain.chunks",
            "directCell.availableChunks",
            "directCell.missingChunks"
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
        long attempts = get(DIRECT_ATTEMPTS);
        long hits = get(DIRECT_SOLID_HITS) + get(DIRECT_AIR_HITS);
        long slow = get(SLOW_SAMPLES);
        return new Snapshot(
                ENABLED,
                chunks,
                millis(nanos),
                chunks == 0L ? 0.0D : (double) nanos / (double) chunks,
                attempts,
                hits,
                attempts == 0L ? 0.0D : (double) hits / (double) attempts,
                slow,
                get(CELL_CACHE_EAGER_FILLS),
                get(CELL_CACHE_LAZY_FILLS),
                get(POSITIVE_DENSITY_ORE_FAST_SAMPLES),
                get(DIRECT_SOLID_CELL_FAST_CELLS),
                get(DIRECT_SOLID_CELL_FAST_BLOCKS),
                get(DIRECT_NEGATIVE_GLOBAL_FLUID_FAST_SAMPLES),
                get(DIRECT_HIGH_AIR_CELL_FAST_CELLS),
                get(DIRECT_HIGH_AIR_CELL_FAST_BLOCKS),
                get(DIRECT_GLOBAL_LAVA_CELL_FAST_CELLS),
                get(DIRECT_GLOBAL_LAVA_CELL_FAST_BLOCKS),
                get(DIRECT_SOLID_CELL_BULK_WRITES),
                get(CELL_DENSITY_CLASSIFIER_HITS),
                get(CELL_DENSITY_CLASSIFIER_SCAN_FALLBACKS),
                get(CELL_DENSITY_SUMMARY_INTEGRATED),
                get(CELL_DENSITY_SUMMARY_SCAN_FALLBACKS),
                get(CELL_DENSITY_SUMMARY_FAST_FAILURES),
                get(DIRECT_HIGH_AIR_SURFACE_FAST_CELLS),
                get(DIRECT_HIGH_AIR_SURFACE_FAST_BLOCKS),
                get(FUSED_TERRAIN_CHUNKS),
                get(DIRECT_CELL_AVAILABLE_CHUNKS),
                get(DIRECT_CELL_MISSING_CHUNKS),
                summary()
        );
    }

    public static String summary() {
        long attempts = get(DIRECT_ATTEMPTS);
        long hits = get(DIRECT_SOLID_HITS) + get(DIRECT_AIR_HITS);
        long chunks = get(DO_FILL_CHUNKS);
        return "NoiseFill metrics: chunks=" + chunks
                + ", totalMs=" + millis(get(DO_FILL_NANOS))
                + ", directEligibleChunks=" + get(DIRECT_ELIGIBLE_CHUNKS)
                + ", directAttempts=" + attempts
                + ", directHits=" + hits
                + ", directHitRate=" + ratio(hits, attempts)
                + ", directSolidHits=" + get(DIRECT_SOLID_HITS)
                + ", directAirHits=" + get(DIRECT_AIR_HITS)
                + ", fallbackOreRange=" + get(DIRECT_FALLBACK_ORE_VEIN_RANGE)
                + ", fallbackNonSolid=" + get(DIRECT_FALLBACK_NON_SOLID)
                + ", slowSamples=" + get(SLOW_SAMPLES)
                + ", fusedFallbacksToVanilla=" + get(FUSED_FALLBACKS_TO_VANILLA)
                + ", updateY=" + get(UPDATE_Y_CALLS)
                + ", updateX=" + get(UPDATE_X_CALLS)
                + ", updateZ=" + get(UPDATE_Z_CALLS)
                + ", skippedY=" + get(UPDATE_Y_SKIPPED_BY_DIRECT)
                + ", skippedX=" + get(UPDATE_X_SKIPPED_BY_DIRECT)
                + ", skippedZ=" + get(UPDATE_Z_SKIPPED_BY_DIRECT)
                + ", selectCellMs=" + millis(get(SELECT_CELL_NANOS))
                + ", eagerCellFills=" + get(CELL_CACHE_EAGER_FILLS)
                + ", lazyCellFills=" + get(CELL_CACHE_LAZY_FILLS)
                + ", lazyCellHits=" + get(CELL_CACHE_LAZY_HITS)
                + ", terrainPrefills=" + get(TERRAIN_CACHE_PREFILLS)
                + ", positiveDensityOreFastSamples=" + get(POSITIVE_DENSITY_ORE_FAST_SAMPLES)
                + ", lateArrayEpochs=" + get(CELL_CACHE_LATE_ARRAY_EPOCHS)
                + ", solidCellFastCells=" + get(DIRECT_SOLID_CELL_FAST_CELLS)
                + ", solidCellFastBlocks=" + get(DIRECT_SOLID_CELL_FAST_BLOCKS)
                + ", negativeGlobalFluidFastSamples=" + get(DIRECT_NEGATIVE_GLOBAL_FLUID_FAST_SAMPLES)
                + ", highAirCellFastCells=" + get(DIRECT_HIGH_AIR_CELL_FAST_CELLS)
                + ", highAirCellFastBlocks=" + get(DIRECT_HIGH_AIR_CELL_FAST_BLOCKS)
                + ", globalLavaCellFastCells=" + get(DIRECT_GLOBAL_LAVA_CELL_FAST_CELLS)
                + ", globalLavaCellFastBlocks=" + get(DIRECT_GLOBAL_LAVA_CELL_FAST_BLOCKS)
                + ", solidCellBulkWrites=" + get(DIRECT_SOLID_CELL_BULK_WRITES)
                + ", densityClassifierHits=" + get(CELL_DENSITY_CLASSIFIER_HITS)
                + ", densityClassifierScanFallbacks=" + get(CELL_DENSITY_CLASSIFIER_SCAN_FALLBACKS)
                + ", densitySummaryIntegrated=" + get(CELL_DENSITY_SUMMARY_INTEGRATED)
                + ", densitySummaryScanFallbacks=" + get(CELL_DENSITY_SUMMARY_SCAN_FALLBACKS)
                + ", densitySummaryFastFailures=" + get(CELL_DENSITY_SUMMARY_FAST_FAILURES)
                + ", highAirSurfaceFastCells=" + get(DIRECT_HIGH_AIR_SURFACE_FAST_CELLS)
                + ", highAirSurfaceFastBlocks=" + get(DIRECT_HIGH_AIR_SURFACE_FAST_BLOCKS)
                + ", fusedTerrainChunks=" + get(FUSED_TERRAIN_CHUNKS)
                + ", directCellAvailableChunks=" + get(DIRECT_CELL_AVAILABLE_CHUNKS)
                + ", directCellMissingChunks=" + get(DIRECT_CELL_MISSING_CHUNKS);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0D : (double) numerator / (double) denominator;
    }

    public record Snapshot(
            boolean enabled,
            long chunks,
            long totalMillis,
            double avgNanosPerChunk,
            long directAttempts,
            long directHits,
            double directHitRate,
            long slowSamples,
            long eagerCellFills,
            long lazyCellFills,
            long positiveDensityOreFastSamples,
            long directSolidCellFastCells,
            long directSolidCellFastBlocks,
            long directNegativeGlobalFluidFastSamples,
            long directHighAirCellFastCells,
            long directHighAirCellFastBlocks,
            long directGlobalLavaCellFastCells,
            long directGlobalLavaCellFastBlocks,
            long directSolidCellBulkWrites,
            long densityClassifierHits,
            long densityClassifierScanFallbacks,
            long densitySummaryIntegrated,
            long densitySummaryScanFallbacks,
            long densitySummaryFastFailures,
            long directHighAirSurfaceFastCells,
            long directHighAirSurfaceFastBlocks,
            long fusedTerrainChunks,
            long directCellAvailableChunks,
            long directCellMissingChunks,
            String summary
    ) {
    }
}
