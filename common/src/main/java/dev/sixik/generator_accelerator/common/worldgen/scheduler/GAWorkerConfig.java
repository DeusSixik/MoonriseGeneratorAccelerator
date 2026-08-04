package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;
import java.util.Locale;

public record GAWorkerConfig(
        boolean chunkSchedulerEnabled,
        Mode mode,
        int workers,
        int maxParkNanos,
        int batchMaxCenters,
        int batchMaxNodes,
        int batchMaxEdges,
        long maxArenaBytesPerBatch,
        int maxArenas,
        int maxQueuedHandlesPerWorker,
        int maxExternalWaiters,
        boolean debugMetrics,
        long rollbackDrainTimeoutMicros,
        long schedulerMaxMemoryBytes,
        boolean compatRefuseUnknownChunkScheduler,
        String[] forceLegacyStatuses
) {
    public enum Mode {
        LIVE_BALANCED,
        PREGEN_THROUGHPUT;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return LIVE_BALANCED;
            }
            try {
                return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return LIVE_BALANCED;
            }
        }
    }

    public static GAWorkerConfig from(GAConfig config, int processors, boolean isDev) {
        Mode mode = Mode.parse(config.schedulerV2Mode);
        int available = Math.max(1, processors);
        int defaultWorkers = switch (mode) {
            case LIVE_BALANCED -> clamp(2, available - (isDev ? 0 : 1), 14);
            case PREGEN_THROUGHPUT -> clamp(2, available - (isDev ? 0 : 1), 16);
        };
        int workers = config.schedulerV2Workers > 0 ? config.schedulerV2Workers : defaultWorkers;
        workers = Math.max(1, Math.min(0x7fff, workers));

        int batchMaxCenters = positiveOrDefault(config.schedulerV2BatchMaxCenters,
                mode == Mode.LIVE_BALANCED ? 24 : 64);
        int batchMaxNodes = positiveOrDefault(config.schedulerV2BatchMaxNodes,
                mode == Mode.LIVE_BALANCED ? 1024 : 3072);
        int batchMaxEdges = positiveOrDefault(config.schedulerV2BatchMaxEdges,
                mode == Mode.LIVE_BALANCED ? 8192 : 24576);
        long arenaBytes = positiveOrDefault(config.schedulerV2MaxArenaBytesPerBatch,
                mode == Mode.LIVE_BALANCED ? 1024L * 1024L : 4L * 1024L * 1024L);
        int maxArenas = positiveOrDefault(config.schedulerV2MaxArenas,
                mode == Mode.LIVE_BALANCED ? 16 : 32);
        int queued = positiveOrDefault(config.schedulerV2MaxQueuedHandlesPerWorker,
                mode == Mode.LIVE_BALANCED ? 8192 : 16384);
        int externalWaiters = positiveOrDefault(config.schedulerV2MaxExternalWaiters, 131072);
        int maxParkNanos = positiveOrDefault(config.schedulerV2MaxParkNanos,
                mode == Mode.LIVE_BALANCED ? 75_000 : 100_000);
        long rollbackDrainMicros = positiveOrDefault(config.schedulerV2RollbackDrainTimeoutMicros,
                mode == Mode.LIVE_BALANCED ? 2_000_000L : 5_000_000L);
        long heapMax = Runtime.getRuntime().maxMemory();
        long defaultMemory = mode == Mode.LIVE_BALANCED
                ? Math.min(64L * 1024L * 1024L, Math.max(16L * 1024L * 1024L, heapMax / 64L))
                : Math.min(192L * 1024L * 1024L, Math.max(32L * 1024L * 1024L, heapMax / 32L));
        long schedulerMemory = positiveOrDefault(config.schedulerV2MaxMemoryBytes, defaultMemory);
        String[] forcedLegacy = splitCsv(config.schedulerV2ForceLegacyStatuses);

        return new GAWorkerConfig(
                config.schedulerV2Enabled,
                mode,
                workers,
                maxParkNanos,
                batchMaxCenters,
                batchMaxNodes,
                batchMaxEdges,
                arenaBytes,
                maxArenas,
                queued,
                externalWaiters,
                config.schedulerV2DebugMetrics,
                rollbackDrainMicros,
                schedulerMemory,
                config.schedulerV2CompatRefuseUnknownChunkScheduler,
                forcedLegacy
        );
    }

    public boolean forceLegacyStatus(String statusName) {
        if (statusName == null || forceLegacyStatuses.length == 0) {
            return false;
        }
        for (String forced : forceLegacyStatuses) {
            if (forced.equalsIgnoreCase(statusName)) {
                return true;
            }
        }
        return false;
    }

    public long estimatedQueueBytes() {
        return (long) workers * (long) maxQueuedHandlesPerWorker * Long.BYTES * 4L;
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("chunkSchedulerEnabled", chunkSchedulerEnabled);
        out.put("mode", mode.name());
        out.put("workers", workers);
        out.put("maxParkNanos", maxParkNanos);
        out.put("batchMaxCenters", batchMaxCenters);
        out.put("batchMaxNodes", batchMaxNodes);
        out.put("batchMaxEdges", batchMaxEdges);
        out.put("maxArenaBytesPerBatch", maxArenaBytesPerBatch);
        out.put("maxArenas", maxArenas);
        out.put("maxQueuedHandlesPerWorker", maxQueuedHandlesPerWorker);
        out.put("maxExternalWaiters", maxExternalWaiters);
        out.put("debugMetrics", debugMetrics);
        out.put("rollbackDrainTimeoutMicros", rollbackDrainTimeoutMicros);
        out.put("schedulerMaxMemoryBytes", schedulerMaxMemoryBytes);
        out.put("compatRefuseUnknownChunkScheduler", compatRefuseUnknownChunkScheduler);
        out.put("forceLegacyStatuses", Arrays.asList(forceLegacyStatuses));
        return out;
    }

    private static int clamp(int min, int value, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positiveOrDefault(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    private static String[] splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }
}
