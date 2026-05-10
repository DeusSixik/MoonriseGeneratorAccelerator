package dev.sixik.generator_accelerator.common.worldgen.workspace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class GAChunkWorkspaceMetrics {
    private static final AtomicLong GLOBAL_IMPORT_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_COMPUTE_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_FINALIZE_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_REPACK_NANOS = new AtomicLong();

    private long importNanos;
    private long computeNanos;
    private long finalizeNanos;
    private long repackNanos;
    private long estimatedRetainedBytes;

    public void clear() {
        importNanos = 0L;
        computeNanos = 0L;
        finalizeNanos = 0L;
        repackNanos = 0L;
        estimatedRetainedBytes = 0L;
    }

    public void addImportNanos(long nanos) {
        long positive = Math.max(0L, nanos);
        importNanos += positive;
        GLOBAL_IMPORT_NANOS.addAndGet(positive);
    }

    public void addComputeNanos(long nanos) {
        long positive = Math.max(0L, nanos);
        computeNanos += positive;
        GLOBAL_COMPUTE_NANOS.addAndGet(positive);
    }

    public void addFinalizeNanos(long nanos) {
        long positive = Math.max(0L, nanos);
        finalizeNanos += positive;
        GLOBAL_FINALIZE_NANOS.addAndGet(positive);
    }

    public void addRepackNanos(long nanos) {
        long positive = Math.max(0L, nanos);
        repackNanos += positive;
        GLOBAL_REPACK_NANOS.addAndGet(positive);
    }

    void setEstimatedRetainedBytes(long estimatedRetainedBytes) {
        this.estimatedRetainedBytes = estimatedRetainedBytes;
    }

    public long importNanos() {
        return importNanos;
    }

    public long computeNanos() {
        return computeNanos;
    }

    public long finalizeNanos() {
        return finalizeNanos;
    }

    public long repackNanos() {
        return repackNanos;
    }

    public long estimatedRetainedBytes() {
        return estimatedRetainedBytes;
    }

    public static Map<String, Object> snapshotGlobal() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importNanos", GLOBAL_IMPORT_NANOS.get());
        out.put("computeNanos", GLOBAL_COMPUTE_NANOS.get());
        out.put("finalizeNanos", GLOBAL_FINALIZE_NANOS.get());
        out.put("repackNanos", GLOBAL_REPACK_NANOS.get());
        return out;
    }

    public static void resetGlobal() {
        GLOBAL_IMPORT_NANOS.set(0L);
        GLOBAL_COMPUTE_NANOS.set(0L);
        GLOBAL_FINALIZE_NANOS.set(0L);
        GLOBAL_REPACK_NANOS.set(0L);
    }
}
