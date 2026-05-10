package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineScratch;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.atomic.AtomicLong;

public final class FeatureMemoryDebug {
    public static final boolean ENABLED = Boolean.getBoolean("ga.features.memoryDebug");
    private static final long LOG_EVERY_CHUNKS = Math.max(1L, Long.getLong("ga.features.memoryDebugEvery", 256L));
    private static final AtomicLong DECORATED_CHUNKS = new AtomicLong();

    private FeatureMemoryDebug() {
    }

    public static void maybeLogDecorationChunk(ChunkPos chunkPos, int biomeCount, DecorationPipelineScratch scratch) {
        if (!ENABLED) {
            return;
        }

        long decorated = DECORATED_CHUNKS.incrementAndGet();
        if ((decorated % LOG_EVERY_CHUNKS) != 0L) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMiB = bytesToMiB(runtime.totalMemory() - runtime.freeMemory());
        long committedMiB = bytesToMiB(runtime.totalMemory());
        long maxMiB = bytesToMiB(runtime.maxMemory());
        GeneratorAccelerator.LOGGER.info(
                "GA feature memory debug: decoratedChunks={}, chunk=[{}, {}], biomes={}, heapUsedMiB={}, heapCommittedMiB={}, heapMaxMiB={}, scratch={}",
                decorated,
                chunkPos.x,
                chunkPos.z,
                biomeCount,
                usedMiB,
                committedMiB,
                maxMiB,
                scratch.debugSummary()
        );
    }

    private static long bytesToMiB(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
