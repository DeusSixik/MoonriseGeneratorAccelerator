package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class DfcOpenClStats {
    private static final LongAdder SLAB_ATTEMPTS = new LongAdder();
    private static final LongAdder SLAB_SUBMITTED = new LongAdder();
    private static final LongAdder SLAB_SUCCEEDED = new LongAdder();
    private static final LongAdder SLAB_FAILED = new LongAdder();
    private static final LongAdder SLAB_SKIPPED_DISABLED = new LongAdder();
    private static final LongAdder SLAB_SKIPPED_UNAVAILABLE = new LongAdder();
    private static final LongAdder SLAB_SKIPPED_BROKEN = new LongAdder();
    private static final LongAdder SLAB_SKIPPED_BELOW_MIN = new LongAdder();
    private static final LongAdder SLAB_FALLBACK_JNI = new LongAdder();
    private static final LongAdder SLAB_FALLBACK_JAVA = new LongAdder();
    private static final LongAdder SLAB_ELEMENTS = new LongAdder();
    private static final LongAdder SLAB_NANOS = new LongAdder();
    private static final AtomicLong SLAB_MAX_NANOS = new AtomicLong();

    private DfcOpenClStats() {
    }

    static void recordSlabAttempt(int n) {
        SLAB_ATTEMPTS.increment();
        if (n > 0) {
            SLAB_ELEMENTS.add(n);
        }
    }

    static void recordSlabSubmitted() {
        SLAB_SUBMITTED.increment();
    }

    static void recordSlabSuccess(long nanos) {
        SLAB_SUCCEEDED.increment();
        SLAB_NANOS.add(Math.max(0L, nanos));
        updateMax(SLAB_MAX_NANOS, nanos);
    }

    static void recordSlabFailure() {
        SLAB_FAILED.increment();
    }

    static void recordSlabSkippedDisabled() {
        SLAB_SKIPPED_DISABLED.increment();
    }

    static void recordSlabSkippedUnavailable() {
        SLAB_SKIPPED_UNAVAILABLE.increment();
    }

    static void recordSlabSkippedBroken() {
        SLAB_SKIPPED_BROKEN.increment();
    }

    static void recordSlabSkippedBelowMin() {
        SLAB_SKIPPED_BELOW_MIN.increment();
    }

    public static void recordSlabFallbackJni() {
        SLAB_FALLBACK_JNI.increment();
    }

    public static void recordSlabFallbackJava() {
        SLAB_FALLBACK_JAVA.increment();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                SLAB_ATTEMPTS.sum(),
                SLAB_SUBMITTED.sum(),
                SLAB_SUCCEEDED.sum(),
                SLAB_FAILED.sum(),
                SLAB_SKIPPED_DISABLED.sum(),
                SLAB_SKIPPED_UNAVAILABLE.sum(),
                SLAB_SKIPPED_BROKEN.sum(),
                SLAB_SKIPPED_BELOW_MIN.sum(),
                SLAB_FALLBACK_JNI.sum(),
                SLAB_FALLBACK_JAVA.sum(),
                SLAB_ELEMENTS.sum(),
                SLAB_NANOS.sum(),
                SLAB_MAX_NANOS.get(),
                DfcOpenClConfig.slabVmMinElements());
    }

    public static void reset() {
        SLAB_ATTEMPTS.reset();
        SLAB_SUBMITTED.reset();
        SLAB_SUCCEEDED.reset();
        SLAB_FAILED.reset();
        SLAB_SKIPPED_DISABLED.reset();
        SLAB_SKIPPED_UNAVAILABLE.reset();
        SLAB_SKIPPED_BROKEN.reset();
        SLAB_SKIPPED_BELOW_MIN.reset();
        SLAB_FALLBACK_JNI.reset();
        SLAB_FALLBACK_JAVA.reset();
        SLAB_ELEMENTS.reset();
        SLAB_NANOS.reset();
        SLAB_MAX_NANOS.set(0L);
    }

    private static void updateMax(AtomicLong target, long value) {
        long clamped = Math.max(0L, value);
        long prev;
        do {
            prev = target.get();
            if (clamped <= prev) {
                return;
            }
        } while (!target.compareAndSet(prev, clamped));
    }

    public record Snapshot(
            long slabAttempts,
            long slabSubmitted,
            long slabSucceeded,
            long slabFailed,
            long slabSkippedDisabled,
            long slabSkippedUnavailable,
            long slabSkippedBroken,
            long slabSkippedBelowMin,
            long slabFallbackJni,
            long slabFallbackJava,
            long slabElements,
            long slabNanos,
            long slabMaxNanos,
            int slabMinElements) {
    }
}
