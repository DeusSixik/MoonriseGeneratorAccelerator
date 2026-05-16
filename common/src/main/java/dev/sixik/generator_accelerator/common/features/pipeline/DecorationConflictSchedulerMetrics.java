package dev.sixik.generator_accelerator.common.features.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class DecorationConflictSchedulerMetrics {
    private static final AtomicLong BATCHES_SUBMITTED = new AtomicLong();
    private static final AtomicLong BATCHES_COMMITTED = new AtomicLong();
    private static final AtomicLong FEATURES_SUBMITTED = new AtomicLong();
    private static final AtomicLong FEATURES_COMMITTED = new AtomicLong();
    private static final AtomicLong JOURNAL_WRITES_COMMITTED = new AtomicLong();
    private static final AtomicLong CONFLICT_FALLBACKS = new AtomicLong();
    private static final AtomicLong FAILURE_FALLBACKS = new AtomicLong();
    private static final AtomicLong SMALL_BATCH_FALLBACKS = new AtomicLong();

    private DecorationConflictSchedulerMetrics() {
    }

    static void recordSubmitted(int featureCount) {
        BATCHES_SUBMITTED.incrementAndGet();
        FEATURES_SUBMITTED.addAndGet(Math.max(0, featureCount));
    }

    static void recordCommitted(int featureCount, long writes) {
        BATCHES_COMMITTED.incrementAndGet();
        FEATURES_COMMITTED.addAndGet(Math.max(0, featureCount));
        JOURNAL_WRITES_COMMITTED.addAndGet(Math.max(0L, writes));
    }

    static void recordConflictFallback() {
        CONFLICT_FALLBACKS.incrementAndGet();
    }

    static void recordFailureFallback() {
        FAILURE_FALLBACKS.incrementAndGet();
    }

    static void recordSmallBatchFallback() {
        SMALL_BATCH_FALLBACKS.incrementAndGet();
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchesSubmitted", BATCHES_SUBMITTED.get());
        out.put("batchesCommitted", BATCHES_COMMITTED.get());
        out.put("featuresSubmitted", FEATURES_SUBMITTED.get());
        out.put("featuresCommitted", FEATURES_COMMITTED.get());
        out.put("journalWritesCommitted", JOURNAL_WRITES_COMMITTED.get());
        out.put("conflictFallbacks", CONFLICT_FALLBACKS.get());
        out.put("failureFallbacks", FAILURE_FALLBACKS.get());
        out.put("smallBatchFallbacks", SMALL_BATCH_FALLBACKS.get());
        return out;
    }

    public static void reset() {
        BATCHES_SUBMITTED.set(0L);
        BATCHES_COMMITTED.set(0L);
        FEATURES_SUBMITTED.set(0L);
        FEATURES_COMMITTED.set(0L);
        JOURNAL_WRITES_COMMITTED.set(0L);
        CONFLICT_FALLBACKS.set(0L);
        FAILURE_FALLBACKS.set(0L);
        SMALL_BATCH_FALLBACKS.set(0L);
    }
}
