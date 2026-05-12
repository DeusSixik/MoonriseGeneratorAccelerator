package dev.sixik.generator_accelerator.common.worldgen.workspace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class GAChunkWorkspaceMetrics {
    private static final AtomicLong GLOBAL_IMPORT_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_COMPUTE_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_FINALIZE_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_REPACK_NANOS = new AtomicLong();
    private static final AtomicLong GLOBAL_IMPORT_FAILURES = new AtomicLong();
    private static final AtomicLong GLOBAL_FINALIZE_FAILURES = new AtomicLong();
    private static final AtomicLong GLOBAL_TERRAIN_PASSES = new AtomicLong();
    private static final AtomicLong GLOBAL_TERRAIN_BLOCK_WRITES = new AtomicLong();
    private static final AtomicLong GLOBAL_CARVED_BLOCKS = new AtomicLong();
    private static final AtomicLong GLOBAL_SURFACE_SCANNED_COLUMNS = new AtomicLong();
    private static final AtomicLong GLOBAL_HEIGHT_UPDATES = new AtomicLong();
    private static final AtomicLong GLOBAL_FINALIZED_WORKSPACES = new AtomicLong();
    private static final AtomicLong GLOBAL_TERRAIN_FAILURES = new AtomicLong();

    private long importNanos;
    private long computeNanos;
    private long finalizeNanos;
    private long repackNanos;
    private long terrainPasses;
    private long terrainBlockWrites;
    private long carvedBlocks;
    private long surfaceScannedColumns;
    private long heightUpdates;
    private long finalizedWorkspaces;
    private long estimatedRetainedBytes;

    public void clear() {
        importNanos = 0L;
        computeNanos = 0L;
        finalizeNanos = 0L;
        repackNanos = 0L;
        terrainPasses = 0L;
        terrainBlockWrites = 0L;
        carvedBlocks = 0L;
        surfaceScannedColumns = 0L;
        heightUpdates = 0L;
        finalizedWorkspaces = 0L;
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

    public static void incrementImportFailures() {
        GLOBAL_IMPORT_FAILURES.incrementAndGet();
    }

    public static void incrementFinalizeFailures() {
        GLOBAL_FINALIZE_FAILURES.incrementAndGet();
    }

    public void incrementTerrainPasses() {
        terrainPasses++;
        GLOBAL_TERRAIN_PASSES.incrementAndGet();
    }

    public void addTerrainBlockWrites(long blocks) {
        long positive = Math.max(0L, blocks);
        terrainBlockWrites += positive;
        GLOBAL_TERRAIN_BLOCK_WRITES.addAndGet(positive);
    }

    public void addCarvedBlocks(long blocks) {
        long positive = Math.max(0L, blocks);
        carvedBlocks += positive;
        GLOBAL_CARVED_BLOCKS.addAndGet(positive);
    }

    public void addSurfaceScannedColumns(long columns) {
        long positive = Math.max(0L, columns);
        surfaceScannedColumns += positive;
        GLOBAL_SURFACE_SCANNED_COLUMNS.addAndGet(positive);
    }

    public void addHeightUpdates(long columns) {
        long positive = Math.max(0L, columns);
        heightUpdates += positive;
        GLOBAL_HEIGHT_UPDATES.addAndGet(positive);
    }

    public void incrementFinalizedWorkspaces() {
        finalizedWorkspaces++;
        GLOBAL_FINALIZED_WORKSPACES.incrementAndGet();
    }

    public static void incrementTerrainFailures() {
        GLOBAL_TERRAIN_FAILURES.incrementAndGet();
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

    public long terrainPasses() {
        return terrainPasses;
    }

    public long terrainBlockWrites() {
        return terrainBlockWrites;
    }

    public long carvedBlocks() {
        return carvedBlocks;
    }

    public long surfaceScannedColumns() {
        return surfaceScannedColumns;
    }

    public long heightUpdates() {
        return heightUpdates;
    }

    public long finalizedWorkspaces() {
        return finalizedWorkspaces;
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
        out.put("importFailures", GLOBAL_IMPORT_FAILURES.get());
        out.put("finalizeFailures", GLOBAL_FINALIZE_FAILURES.get());
        out.put("terrainPasses", GLOBAL_TERRAIN_PASSES.get());
        out.put("terrainBlockWrites", GLOBAL_TERRAIN_BLOCK_WRITES.get());
        out.put("carvedBlocks", GLOBAL_CARVED_BLOCKS.get());
        out.put("surfaceScannedColumns", GLOBAL_SURFACE_SCANNED_COLUMNS.get());
        out.put("heightUpdates", GLOBAL_HEIGHT_UPDATES.get());
        out.put("finalizedWorkspaces", GLOBAL_FINALIZED_WORKSPACES.get());
        out.put("terrainFailures", GLOBAL_TERRAIN_FAILURES.get());
        return out;
    }

    public static void resetGlobal() {
        GLOBAL_IMPORT_NANOS.set(0L);
        GLOBAL_COMPUTE_NANOS.set(0L);
        GLOBAL_FINALIZE_NANOS.set(0L);
        GLOBAL_REPACK_NANOS.set(0L);
        GLOBAL_IMPORT_FAILURES.set(0L);
        GLOBAL_FINALIZE_FAILURES.set(0L);
        GLOBAL_TERRAIN_PASSES.set(0L);
        GLOBAL_TERRAIN_BLOCK_WRITES.set(0L);
        GLOBAL_CARVED_BLOCKS.set(0L);
        GLOBAL_SURFACE_SCANNED_COLUMNS.set(0L);
        GLOBAL_HEIGHT_UPDATES.set(0L);
        GLOBAL_FINALIZED_WORKSPACES.set(0L);
        GLOBAL_TERRAIN_FAILURES.set(0L);
    }
}
