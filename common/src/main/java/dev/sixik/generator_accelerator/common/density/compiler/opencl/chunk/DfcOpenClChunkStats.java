package dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class DfcOpenClChunkStats {
    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder SUCCEEDED = new LongAdder();
    private static final LongAdder FAILED = new LongAdder();
    private static final LongAdder CHUNKS = new LongAdder();
    private static final LongAdder BATCHES = new LongAdder();
    private static final LongAdder OUTPUT_BYTES = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final AtomicLong MAX_NANOS = new AtomicLong();
    private static final AtomicReference<String> LAST_SKIP = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_FAILURE = new AtomicReference<>("");

    private DfcOpenClChunkStats() {
    }

    public static void recordCall() {
        CALLS.increment();
    }

    public static void recordSkip(String reason) {
        SKIPPED.increment();
        LAST_SKIP.set(cleanReason(reason, "skipped"));
    }

    public static void recordAttempt(int chunks, long outputBytes) {
        ATTEMPTS.increment();
    }

    public static void recordSuccess(int chunks, long outputBytes, long nanos) {
        SUCCEEDED.increment();
        BATCHES.increment();
        if (chunks > 0) {
            CHUNKS.add(chunks);
        }
        if (outputBytes > 0L) {
            OUTPUT_BYTES.add(outputBytes);
        }
        long clampedNanos = Math.max(0L, nanos);
        TOTAL_NANOS.add(clampedNanos);
        updateMax(MAX_NANOS, clampedNanos);
    }

    public static void recordFailure(String reason) {
        FAILED.increment();
        LAST_FAILURE.set(cleanReason(reason, "failed"));
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                CALLS.sum(),
                SKIPPED.sum(),
                ATTEMPTS.sum(),
                SUCCEEDED.sum(),
                FAILED.sum(),
                CHUNKS.sum(),
                BATCHES.sum(),
                OUTPUT_BYTES.sum(),
                TOTAL_NANOS.sum(),
                MAX_NANOS.get(),
                LAST_SKIP.get(),
                LAST_FAILURE.get());
    }

    public static void reset() {
        CALLS.reset();
        SKIPPED.reset();
        ATTEMPTS.reset();
        SUCCEEDED.reset();
        FAILED.reset();
        CHUNKS.reset();
        BATCHES.reset();
        OUTPUT_BYTES.reset();
        TOTAL_NANOS.reset();
        MAX_NANOS.set(0L);
        LAST_SKIP.set("");
        LAST_FAILURE.set("");
    }

    private static String cleanReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static void updateMax(AtomicLong target, long value) {
        long prev;
        do {
            prev = target.get();
            if (value <= prev) {
                return;
            }
        } while (!target.compareAndSet(prev, value));
    }

    public record Snapshot(
            long calls,
            long skipped,
            long attempts,
            long succeeded,
            long failed,
            long chunks,
            long batches,
            long outputBytes,
            long totalNanos,
            long maxNanos,
            String lastSkip,
            String lastFailure) {
    }
}
