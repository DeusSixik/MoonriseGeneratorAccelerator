package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small admission gate for Phase 1 workspaces. If no permit is available the
 * caller keeps the conservative vanilla path instead of over-allocating heap.
 */
public final class GAChunkWorkspacePool {
    private static final Object INIT_LOCK = new Object();
    private static final ConcurrentLinkedQueue<GAChunkWorkspace> POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger POOLED = new AtomicInteger();
    private static final AtomicInteger MAX_IN_FLIGHT_SEEN = new AtomicInteger();
    private static final AtomicLong ACQUIRE_ATTEMPTS = new AtomicLong();
    private static final AtomicLong ACQUIRED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong REUSED = new AtomicLong();
    private static final AtomicLong CREATED = new AtomicLong();
    private static final AtomicLong RELEASED = new AtomicLong();

    private static volatile boolean initialized;
    private static volatile Semaphore permits;
    private static volatile int maxInFlight;
    private static volatile int maxRetainedWorkspaces;
    private static volatile int maxRetainedBlockInts;

    private GAChunkWorkspacePool() {
    }

    public static GAChunkWorkspace acquire(ChunkAccess chunk) {
        return acquire(chunk, false);
    }

    public static GAChunkWorkspace acquire(ChunkAccess chunk, boolean allocateBlockBuffer) {
        ensureInitialized();
        ACQUIRE_ATTEMPTS.incrementAndGet();
        if (!permits.tryAcquire()) {
            REJECTED.incrementAndGet();
            return null;
        }

        GAChunkWorkspace workspace = pollRetained();
        if (workspace == null) {
            workspace = new GAChunkWorkspace(maxRetainedBlockInts, GAChunkWorkspace.COLUMN_COUNT, dirtyWordLimit());
            CREATED.incrementAndGet();
        } else {
            REUSED.incrementAndGet();
        }

        try {
            workspace.begin(chunk, allocateBlockBuffer);
            int active = IN_FLIGHT.incrementAndGet();
            updateMaxInFlight(active);
            ACQUIRED.incrementAndGet();
            return workspace;
        } catch (RuntimeException | Error failure) {
            try {
                workspace.release();
                retain(workspace);
            } finally {
                permits.release();
            }
            throw failure;
        }
    }

    public static void release(GAChunkWorkspace workspace) {
        if (workspace == null) {
            return;
        }
        workspace.release();
        retain(workspace);
        RELEASED.incrementAndGet();
        IN_FLIGHT.updateAndGet(active -> Math.max(0, active - 1));
        permits.release();
    }

    public static void clearRetained() {
        if (!initialized) {
            return;
        }
        int cleared = 0;
        while (POOL.poll() != null) {
            cleared++;
        }
        if (cleared > 0) {
            int clearedCount = cleared;
            POOLED.updateAndGet(pooled -> Math.max(0, pooled - clearedCount));
        }
    }

    public static Map<String, Object> snapshot() {
        ensureInitialized();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxInFlight", maxInFlight);
        out.put("maxRetainedWorkspaces", maxRetainedWorkspaces);
        out.put("inFlight", IN_FLIGHT.get());
        out.put("availablePermits", permits.availablePermits());
        out.put("pooled", POOLED.get());
        out.put("maxInFlightSeen", MAX_IN_FLIGHT_SEEN.get());
        out.put("acquireAttempts", ACQUIRE_ATTEMPTS.get());
        out.put("acquired", ACQUIRED.get());
        out.put("rejected", REJECTED.get());
        out.put("created", CREATED.get());
        out.put("reused", REUSED.get());
        out.put("released", RELEASED.get());
        out.put("pooledEstimatedRetainedBytes", pooledEstimatedRetainedBytes());
        out.put("metrics", GAChunkWorkspaceMetrics.snapshotGlobal());
        return out;
    }

    public static void resetMetrics() {
        MAX_IN_FLIGHT_SEEN.set(IN_FLIGHT.get());
        ACQUIRE_ATTEMPTS.set(0L);
        ACQUIRED.set(0L);
        REJECTED.set(0L);
        REUSED.set(0L);
        CREATED.set(0L);
        RELEASED.set(0L);
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            GAConfig config = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            maxInFlight = config.maxInFlightWorkspaces > 0 ? config.maxInFlightWorkspaces : Math.max(1, processors / 2);
            maxRetainedWorkspaces = Math.max(0, intProperty(
                    "ga.chunkWorkspace.pool.maxRetainedWorkspaces",
                    maxInFlight
            ));
            maxRetainedBlockInts = retainedBlockInts(config.workspaceMaxRetainedBytes);
            permits = new Semaphore(maxInFlight);
            initialized = true;
        }
    }

    private static GAChunkWorkspace pollRetained() {
        GAChunkWorkspace workspace = POOL.poll();
        if (workspace != null) {
            POOLED.updateAndGet(pooled -> Math.max(0, pooled - 1));
        }
        return workspace;
    }

    private static boolean retain(GAChunkWorkspace workspace) {
        if (workspace == null || maxRetainedWorkspaces <= 0) {
            return false;
        }
        for (;;) {
            int pooled = POOLED.get();
            if (pooled >= maxRetainedWorkspaces) {
                return false;
            }
            if (POOLED.compareAndSet(pooled, pooled + 1)) {
                POOL.offer(workspace);
                return true;
            }
        }
    }

    private static int intProperty(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int retainedBlockInts(long configuredBytes) {
        if (configuredBytes <= 0L) {
            return GAChunkWorkspace.BLOCKS_PER_SECTION * 24;
        }
        long ints = configuredBytes / Integer.BYTES;
        ints = Math.max(GAChunkWorkspace.BLOCKS_PER_SECTION, ints);
        return (int) Math.min(Integer.MAX_VALUE, ints);
    }

    private static int dirtyWordLimit() {
        return Math.max(1, (maxRetainedBlockInts / GAChunkWorkspace.BLOCKS_PER_SECTION + 63) >>> 6);
    }

    private static long pooledEstimatedRetainedBytes() {
        long bytes = 0L;
        for (GAChunkWorkspace workspace : POOL) {
            bytes += workspace.estimatedRetainedBytes();
        }
        return bytes;
    }

    private static void updateMaxInFlight(int active) {
        int current;
        do {
            current = MAX_IN_FLIGHT_SEEN.get();
            if (active <= current) {
                return;
            }
        } while (!MAX_IN_FLIGHT_SEEN.compareAndSet(current, active));
    }
}
