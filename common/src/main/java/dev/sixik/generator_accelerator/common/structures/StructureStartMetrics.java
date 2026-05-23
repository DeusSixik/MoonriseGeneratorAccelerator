package dev.sixik.generator_accelerator.common.structures;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default-off counters for ChunkGenerator#createStructures root-cause tests.
 */
public final class StructureStartMetrics {
    public static volatile boolean ENABLED =
            Boolean.getBoolean("ga.structures.createStructures.metrics")
                    || Boolean.getBoolean("ga.wallTimeTelemetry")
                    || Boolean.getBoolean("ga.structures.createStructures.typeMetrics.enabled");
    public static volatile boolean TYPE_METRICS_ENABLED =
            Boolean.getBoolean("ga.structures.createStructures.typeMetrics.enabled");

    private static final AtomicLong STRUCTURE_SETS = new AtomicLong();
    private static final AtomicLong DUPLICATE_START_CHECKS = new AtomicLong();
    private static final AtomicLong DUPLICATE_START_ENTRIES = new AtomicLong();
    private static final AtomicLong DUPLICATE_START_HITS = new AtomicLong();
    private static final AtomicLong DUPLICATE_START_NANOS = new AtomicLong();
    private static final AtomicLong DUPLICATE_SNAPSHOT_CHECKS = new AtomicLong();
    private static final AtomicLong FAST_LOOKUP_CHECKS = new AtomicLong();
    private static final AtomicLong FAST_LOOKUP_ENTRIES = new AtomicLong();
    private static final AtomicLong FAST_LOOKUP_HITS = new AtomicLong();
    private static final AtomicLong FAST_LOOKUP_NANOS = new AtomicLong();
    private static final AtomicLong PLACEMENT_CHECKS = new AtomicLong();
    private static final AtomicLong PLACEMENT_HITS = new AtomicLong();
    private static final AtomicLong PLACEMENT_MISSES = new AtomicLong();
    private static final AtomicLong PLACEMENT_NANOS = new AtomicLong();
    private static final AtomicLong TRY_GENERATE_CALLS = new AtomicLong();
    private static final AtomicLong TRY_GENERATE_SUCCESSES = new AtomicLong();
    private static final AtomicLong TRY_GENERATE_NANOS = new AtomicLong();
    private static final AtomicLong WEIGHTED_SELECTIONS = new AtomicLong();
    private static final AtomicLong WEIGHTED_SELECTION_ENTRIES = new AtomicLong();
    private static final AtomicLong WEIGHTED_SELECTION_ROLLS = new AtomicLong();
    private static final AtomicLong WEIGHTED_SELECTION_CANDIDATE_SCANS = new AtomicLong();
    private static final AtomicLong WEIGHTED_SELECTION_NANOS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_REFRESHES = new AtomicLong();
    private static final ConcurrentHashMap<String, TypeStats> TRY_GENERATE_BY_STRUCTURE = new ConcurrentHashMap<>();

    private StructureStartMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled || TYPE_METRICS_ENABLED;
    }

    public static void setTypeMetricsEnabled(boolean enabled) {
        TYPE_METRICS_ENABLED = enabled;
        if (enabled) {
            ENABLED = true;
        }
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordStructureSet() {
        if (ENABLED) {
            STRUCTURE_SETS.incrementAndGet();
        }
    }

    public static void recordDuplicateStartCheck(long startNanos, int entries, boolean hit, boolean snapshot) {
        if (!ENABLED) {
            return;
        }
        DUPLICATE_START_CHECKS.incrementAndGet();
        DUPLICATE_START_ENTRIES.addAndGet(Math.max(0, entries));
        DUPLICATE_START_NANOS.addAndGet(elapsed(startNanos));
        if (hit) {
            DUPLICATE_START_HITS.incrementAndGet();
        }
        if (snapshot) {
            DUPLICATE_SNAPSHOT_CHECKS.incrementAndGet();
        }
    }

    public static void recordFastLookup(long startNanos, int entries, boolean hit) {
        if (!ENABLED) {
            return;
        }
        FAST_LOOKUP_CHECKS.incrementAndGet();
        FAST_LOOKUP_ENTRIES.addAndGet(Math.max(0, entries));
        FAST_LOOKUP_NANOS.addAndGet(elapsed(startNanos));
        if (hit) {
            FAST_LOOKUP_HITS.incrementAndGet();
        }
    }

    public static void recordPlacementCheck(long startNanos, boolean hit) {
        if (!ENABLED) {
            return;
        }
        PLACEMENT_CHECKS.incrementAndGet();
        PLACEMENT_NANOS.addAndGet(elapsed(startNanos));
        if (hit) {
            PLACEMENT_HITS.incrementAndGet();
        } else {
            PLACEMENT_MISSES.incrementAndGet();
        }
    }

    public static void recordTryGenerate(long startNanos, boolean success, String structureName) {
        if (!ENABLED) {
            return;
        }
        long elapsed = elapsed(startNanos);
        TRY_GENERATE_CALLS.incrementAndGet();
        TRY_GENERATE_NANOS.addAndGet(elapsed);
        if (success) {
            TRY_GENERATE_SUCCESSES.incrementAndGet();
        }
        if (TYPE_METRICS_ENABLED && structureName != null && !structureName.isBlank()) {
            TRY_GENERATE_BY_STRUCTURE
                    .computeIfAbsent(structureName, ignored -> new TypeStats())
                    .record(elapsed, success);
        }
    }

    public static void recordWeightedSelection(long startNanos, int entries, int rolls, int candidateScans) {
        if (!ENABLED) {
            return;
        }
        WEIGHTED_SELECTIONS.incrementAndGet();
        WEIGHTED_SELECTION_ENTRIES.addAndGet(Math.max(0, entries));
        WEIGHTED_SELECTION_ROLLS.addAndGet(Math.max(0, rolls));
        WEIGHTED_SELECTION_CANDIDATE_SCANS.addAndGet(Math.max(0, candidateScans));
        WEIGHTED_SELECTION_NANOS.addAndGet(elapsed(startNanos));
    }

    public static void recordSnapshotRefresh() {
        if (ENABLED) {
            SNAPSHOT_REFRESHES.incrementAndGet();
        }
    }

    public static void reset() {
        STRUCTURE_SETS.set(0L);
        DUPLICATE_START_CHECKS.set(0L);
        DUPLICATE_START_ENTRIES.set(0L);
        DUPLICATE_START_HITS.set(0L);
        DUPLICATE_START_NANOS.set(0L);
        DUPLICATE_SNAPSHOT_CHECKS.set(0L);
        FAST_LOOKUP_CHECKS.set(0L);
        FAST_LOOKUP_ENTRIES.set(0L);
        FAST_LOOKUP_HITS.set(0L);
        FAST_LOOKUP_NANOS.set(0L);
        PLACEMENT_CHECKS.set(0L);
        PLACEMENT_HITS.set(0L);
        PLACEMENT_MISSES.set(0L);
        PLACEMENT_NANOS.set(0L);
        TRY_GENERATE_CALLS.set(0L);
        TRY_GENERATE_SUCCESSES.set(0L);
        TRY_GENERATE_NANOS.set(0L);
        WEIGHTED_SELECTIONS.set(0L);
        WEIGHTED_SELECTION_ENTRIES.set(0L);
        WEIGHTED_SELECTION_ROLLS.set(0L);
        WEIGHTED_SELECTION_CANDIDATE_SCANS.set(0L);
        WEIGHTED_SELECTION_NANOS.set(0L);
        SNAPSHOT_REFRESHES.set(0L);
        TRY_GENERATE_BY_STRUCTURE.clear();
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("typeMetricsEnabled", TYPE_METRICS_ENABLED);
        out.put("structureSets", STRUCTURE_SETS.get());
        out.put("duplicateStartChecks", DUPLICATE_START_CHECKS.get());
        out.put("duplicateStartEntries", DUPLICATE_START_ENTRIES.get());
        out.put("duplicateStartHits", DUPLICATE_START_HITS.get());
        out.put("duplicateStartNanos", DUPLICATE_START_NANOS.get());
        out.put("duplicateSnapshotChecks", DUPLICATE_SNAPSHOT_CHECKS.get());
        out.put("fastLookupChecks", FAST_LOOKUP_CHECKS.get());
        out.put("fastLookupEntries", FAST_LOOKUP_ENTRIES.get());
        out.put("fastLookupHits", FAST_LOOKUP_HITS.get());
        out.put("fastLookupNanos", FAST_LOOKUP_NANOS.get());
        out.put("placementChecks", PLACEMENT_CHECKS.get());
        out.put("placementHits", PLACEMENT_HITS.get());
        out.put("placementMisses", PLACEMENT_MISSES.get());
        out.put("placementNanos", PLACEMENT_NANOS.get());
        out.put("tryGenerateCalls", TRY_GENERATE_CALLS.get());
        out.put("tryGenerateSuccesses", TRY_GENERATE_SUCCESSES.get());
        out.put("tryGenerateNanos", TRY_GENERATE_NANOS.get());
        out.put("weightedSelections", WEIGHTED_SELECTIONS.get());
        out.put("weightedSelectionEntries", WEIGHTED_SELECTION_ENTRIES.get());
        out.put("weightedSelectionRolls", WEIGHTED_SELECTION_ROLLS.get());
        out.put("weightedSelectionCandidateScans", WEIGHTED_SELECTION_CANDIDATE_SCANS.get());
        out.put("weightedSelectionNanos", WEIGHTED_SELECTION_NANOS.get());
        out.put("snapshotRefreshes", SNAPSHOT_REFRESHES.get());
        out.put("tryGenerateByStructure", typeSnapshot(16));
        return out;
    }

    public static String summary() {
        if (!ENABLED) {
            return "GA structure start metrics: disabled";
        }
        return "GA structure start metrics: sets=" + STRUCTURE_SETS.get()
                + ", duplicateChecks=" + DUPLICATE_START_CHECKS.get()
                + "/" + DUPLICATE_START_NANOS.get() / 1_000_000L + "ms"
                + ", placementChecks=" + PLACEMENT_CHECKS.get()
                + "/" + PLACEMENT_NANOS.get() / 1_000_000L + "ms"
                + ", placementHits=" + PLACEMENT_HITS.get()
                + ", tryGenerate=" + TRY_GENERATE_CALLS.get()
                + "/" + TRY_GENERATE_NANOS.get() / 1_000_000L + "ms"
                + ", weightedSelections=" + WEIGHTED_SELECTIONS.get()
                + "/" + WEIGHTED_SELECTION_NANOS.get() / 1_000_000L + "ms"
                + ", fastLookupChecks=" + FAST_LOOKUP_CHECKS.get()
                + "/" + FAST_LOOKUP_NANOS.get() / 1_000_000L + "ms";
    }

    private static long elapsed(long startNanos) {
        return startNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - startNanos);
    }

    private static Map<String, Object> typeSnapshot(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!TYPE_METRICS_ENABLED || TRY_GENERATE_BY_STRUCTURE.isEmpty()) {
            return out;
        }
        TRY_GENERATE_BY_STRUCTURE.entrySet()
                .stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, TypeStats> entry) -> entry.getValue().nanos())
                        .reversed())
                .limit(limit)
                .forEach(entry -> out.put(entry.getKey(), entry.getValue().snapshot()));
        return out;
    }

    private static final class TypeStats {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();

        void record(long elapsedNanos, boolean success) {
            calls.incrementAndGet();
            nanos.addAndGet(elapsedNanos);
            if (success) {
                successes.incrementAndGet();
            }
        }

        long nanos() {
            return nanos.get();
        }

        Map<String, Object> snapshot() {
            long callCount = calls.get();
            long nanosTotal = nanos.get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("calls", callCount);
            out.put("successes", successes.get());
            out.put("nanos", nanosTotal);
            out.put("millis", nanosTotal / 1_000_000.0D);
            out.put("avgNanos", callCount == 0L ? 0L : nanosTotal / callCount);
            return out;
        }
    }
}
