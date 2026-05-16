package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable runtime snapshot for commit resolution and replay work.
 */
public record GACommitMetrics(
        int batchCount,
        int inputCount,
        int acceptedCount,
        int rejectedCount,
        int collisionCount,
        long executionNanos,
        int failureCount
) {
    private static final AtomicLong GLOBAL_BATCHES = new AtomicLong();
    private static final AtomicLong GLOBAL_INPUT = new AtomicLong();
    private static final AtomicLong GLOBAL_ACCEPTED = new AtomicLong();
    private static final AtomicLong GLOBAL_REJECTED = new AtomicLong();
    private static final AtomicLong GLOBAL_COLLISIONS = new AtomicLong();
    private static final AtomicLong GLOBAL_EXECUTION_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_FAILURES = new AtomicLong();

    public GACommitMetrics {
        if (batchCount < 0) {
            throw new IllegalArgumentException("batchCount must be non-negative");
        }
        if (inputCount < 0) {
            throw new IllegalArgumentException("inputCount must be non-negative");
        }
        if (acceptedCount < 0) {
            throw new IllegalArgumentException("acceptedCount must be non-negative");
        }
        if (rejectedCount < 0) {
            throw new IllegalArgumentException("rejectedCount must be non-negative");
        }
        if (collisionCount < 0) {
            throw new IllegalArgumentException("collisionCount must be non-negative");
        }
        if (executionNanos < 0L) {
            throw new IllegalArgumentException("executionNanos must be non-negative");
        }
        if (failureCount < 0) {
            throw new IllegalArgumentException("failureCount must be non-negative");
        }
    }

    public static GACommitMetrics empty() {
        return new GACommitMetrics(0, 0, 0, 0, 0, 0L, 0);
    }

    public GACommitMetrics plus(GACommitMetrics other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        return new GACommitMetrics(
                batchCount + other.batchCount,
                inputCount + other.inputCount,
                acceptedCount + other.acceptedCount,
                rejectedCount + other.rejectedCount,
                collisionCount + other.collisionCount,
                executionNanos + other.executionNanos,
                failureCount + other.failureCount
        );
    }

    public static void record(GACommitMetrics metrics) {
        if (metrics == null) {
            return;
        }
        GLOBAL_BATCHES.addAndGet(metrics.batchCount());
        GLOBAL_INPUT.addAndGet(metrics.inputCount());
        GLOBAL_ACCEPTED.addAndGet(metrics.acceptedCount());
        GLOBAL_REJECTED.addAndGet(metrics.rejectedCount());
        GLOBAL_COLLISIONS.addAndGet(metrics.collisionCount());
        GLOBAL_EXECUTION_NANOS.addAndGet(metrics.executionNanos());
        GLOBAL_FAILURES.addAndGet(metrics.failureCount());
    }

    public static Map<String, Object> snapshotGlobal() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batches", GLOBAL_BATCHES.get());
        out.put("input", GLOBAL_INPUT.get());
        out.put("accepted", GLOBAL_ACCEPTED.get());
        out.put("rejected", GLOBAL_REJECTED.get());
        out.put("collisions", GLOBAL_COLLISIONS.get());
        out.put("executionNanos", GLOBAL_EXECUTION_NANOS.get());
        out.put("failures", GLOBAL_FAILURES.get());
        return out;
    }

    public static GACommitMetrics snapshotGlobalMetrics() {
        return new GACommitMetrics(
                toIntExact(GLOBAL_BATCHES.get(), "batches"),
                toIntExact(GLOBAL_INPUT.get(), "input"),
                toIntExact(GLOBAL_ACCEPTED.get(), "accepted"),
                toIntExact(GLOBAL_REJECTED.get(), "rejected"),
                toIntExact(GLOBAL_COLLISIONS.get(), "collisions"),
                GLOBAL_EXECUTION_NANOS.get(),
                toIntExact(GLOBAL_FAILURES.get(), "failures")
        );
    }

    public static void resetGlobal() {
        GLOBAL_BATCHES.set(0L);
        GLOBAL_INPUT.set(0L);
        GLOBAL_ACCEPTED.set(0L);
        GLOBAL_REJECTED.set(0L);
        GLOBAL_COLLISIONS.set(0L);
        GLOBAL_EXECUTION_NANOS.set(0L);
        GLOBAL_FAILURES.set(0L);
    }

    private static int toIntExact(long value, String name) {
        if (value > Integer.MAX_VALUE) {
            throw new ArithmeticException(name + " exceeds int range");
        }
        return (int) value;
    }
}
