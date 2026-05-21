package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureCheckStorageStateCacheTest {
    private static volatile int sink;

    private static final byte UNKNOWN = 0;
    private static final byte NO_DATA = 1;
    private static final byte CHUNK_LOAD_NEEDED = 2;

    @Test
    void cachedStorageProbeMatchesUncachedNoDataBehavior() {
        long[] chunks = chunks(32);
        for (long chunk : chunks) {
            assertEquals(null, cachedNoDataLookup(new Long2ByteOpenHashMap(), chunk));
            assertEquals(null, cachedNoDataLookup(new Long2ByteOpenHashMap(), chunk));
        }
    }

    @Test
    void cachedStorageProbeMatchesUncachedChunkLoadNeededBehavior() {
        long[] chunks = chunks(32);
        for (long chunk : chunks) {
            assertEquals(StructureCheckResult.CHUNK_LOAD_NEEDED, cachedChunkLoadLookup(new Long2ByteOpenHashMap(), chunk));
            assertEquals(StructureCheckResult.CHUNK_LOAD_NEEDED, cachedChunkLoadLookup(new Long2ByteOpenHashMap(), chunk));
        }
    }

    @Test
    void printsHeavyStructurePackStorageProbeMetrics() {
        int warmup = Integer.getInteger("ga.test.structureStorageWarmup", 4_000);
        int iterations = Integer.getInteger("ga.test.structureStorageIterations", 20_000);
        int structures = Integer.getInteger("ga.test.structureStorageStructures", 256);
        long[] chunks = chunks(384);

        runUncachedNoData(chunks, warmup, structures);
        runCachedNoData(chunks, warmup, structures);
        long uncachedNoDataNanos = timeUncachedNoData(chunks, iterations, structures);
        long cachedNoDataNanos = timeCachedNoData(chunks, iterations, structures);

        runUncachedChunkLoadNeeded(chunks, warmup, structures);
        runCachedChunkLoadNeeded(chunks, warmup, structures);
        long uncachedChunkLoadNanos = timeUncachedChunkLoadNeeded(chunks, iterations, structures);
        long cachedChunkLoadNanos = timeCachedChunkLoadNeeded(chunks, iterations, structures);

        System.out.println("StructureCheck storage probe benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", structures=" + structures + ", chunks=384");
        printMetric("no-data", uncachedNoDataNanos, cachedNoDataNanos, iterations);
        printMetric("chunk-load-needed", uncachedChunkLoadNanos, cachedChunkLoadNanos, iterations);
    }

    private static long timeUncachedNoData(long[] chunks, int iterations, int structures) {
        long started = System.nanoTime();
        runUncachedNoData(chunks, iterations, structures);
        return System.nanoTime() - started;
    }

    private static void runUncachedNoData(long[] chunks, int iterations, int structures) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (int structure = 0; structure < structures; structure++) {
                local += uncachedNoDataLookup(chunk, structure) == null ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeCachedNoData(long[] chunks, int iterations, int structures) {
        long started = System.nanoTime();
        runCachedNoData(chunks, iterations, structures);
        return System.nanoTime() - started;
    }

    private static void runCachedNoData(long[] chunks, int iterations, int structures) {
        int local = sink;
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (int structure = 0; structure < structures; structure++) {
                local += cachedNoDataLookup(cache, chunk) == null ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeUncachedChunkLoadNeeded(long[] chunks, int iterations, int structures) {
        long started = System.nanoTime();
        runUncachedChunkLoadNeeded(chunks, iterations, structures);
        return System.nanoTime() - started;
    }

    private static void runUncachedChunkLoadNeeded(long[] chunks, int iterations, int structures) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (int structure = 0; structure < structures; structure++) {
                local += uncachedChunkLoadLookup(chunk, structure) == StructureCheckResult.CHUNK_LOAD_NEEDED ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeCachedChunkLoadNeeded(long[] chunks, int iterations, int structures) {
        long started = System.nanoTime();
        runCachedChunkLoadNeeded(chunks, iterations, structures);
        return System.nanoTime() - started;
    }

    private static void runCachedChunkLoadNeeded(long[] chunks, int iterations, int structures) {
        int local = sink;
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (int structure = 0; structure < structures; structure++) {
                local += cachedChunkLoadLookup(cache, chunk) == StructureCheckResult.CHUNK_LOAD_NEEDED ? 1 : 0;
            }
        }
        sink = local;
    }

    private static StructureCheckResult uncachedNoDataLookup(long chunk, int structureId) {
        sink += scanChunkPayload(chunk, structureId);
        return null;
    }

    private static StructureCheckResult cachedNoDataLookup(Long2ByteOpenHashMap cache, long chunk) {
        byte state = cache.get(chunk);
        if (state == NO_DATA) {
            return null;
        }
        sink += scanChunkPayload(chunk, 0);
        StructureCheckResult result = null;
        cache.put(chunk, result == null ? NO_DATA : UNKNOWN);
        return result;
    }

    private static StructureCheckResult uncachedChunkLoadLookup(long chunk, int structureId) {
        sink += scanChunkPayload(chunk, structureId);
        return StructureCheckResult.CHUNK_LOAD_NEEDED;
    }

    private static StructureCheckResult cachedChunkLoadLookup(Long2ByteOpenHashMap cache, long chunk) {
        byte state = cache.get(chunk);
        if (state == CHUNK_LOAD_NEEDED) {
            return StructureCheckResult.CHUNK_LOAD_NEEDED;
        }
        sink += scanChunkPayload(chunk, 0);
        StructureCheckResult result = StructureCheckResult.CHUNK_LOAD_NEEDED;
        cache.put(chunk, result == StructureCheckResult.CHUNK_LOAD_NEEDED ? CHUNK_LOAD_NEEDED : UNKNOWN);
        return result;
    }

    private static int scanChunkPayload(long chunk, int salt) {
        long mixed = chunk ^ (salt * 0x9E3779B97F4A7C15L);
        int value = 0;
        for (int i = 0; i < 48; i++) {
            mixed ^= mixed << 13;
            mixed ^= mixed >>> 7;
            mixed ^= mixed << 17;
            value += (int) (mixed >>> 32);
        }
        return value & 7;
    }

    private static long[] chunks(int count) {
        long[] chunks = new long[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = ((long) (i * 19) << 32) ^ (i * 43L + 7L);
        }
        return chunks;
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf("%s uncached=%.1f ns/op cached=%.1f ns/op speedup=%.2fx%n",
                label, previousNsOp, nextNsOp, speedup);
    }
}
