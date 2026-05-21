package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureCheckCacheLayoutTest {
    private static volatile int sink;

    @Test
    void chunkCentricCacheMatchesStructureCentricCache() {
        String[] structures = structures(128);
        long[] chunks = chunks(96);
        Map<String, Long2BooleanMap> structureCentric = structureCentric(structures, chunks);
        ChunkKeyedBooleanCache<String> chunkCentric = chunkCentric(structures, chunks);

        for (long chunk : chunks) {
            for (String structure : structures) {
                boolean expected = structureCentric.get(structure).get(chunk);
                boolean actual = chunkCentric.getOrCompute(chunk, structure, () -> {
                    throw new AssertionError("unexpected cache miss");
                });
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void printsHeavyStructurePackCacheMetrics() {
        int warmup = Integer.getInteger("ga.test.structureCheckWarmup", 5_000);
        int iterations = Integer.getInteger("ga.test.structureCheckIterations", 25_000);
        String[] structures = structures(256);
        long[] chunks = chunks(384);
        Map<String, Long2BooleanMap> structureCentric = structureCentric(structures, chunks);
        ChunkKeyedBooleanCache<String> chunkCentric = chunkCentric(structures, chunks);

        runStructureCentricLookup(structureCentric, structures, chunks, warmup);
        runChunkCentricLookup(chunkCentric, structures, chunks, warmup);
        long structureLookupNanos = timeStructureCentricLookup(structureCentric, structures, chunks, iterations);
        long chunkLookupNanos = timeChunkCentricLookup(chunkCentric, structures, chunks, iterations);

        runStructureCentricEviction(structureCentric, structures, chunks, warmup);
        runChunkCentricEviction(chunkCentric, structures, chunks, warmup);
        long structureEvictNanos = timeStructureCentricEviction(structureCentric, structures, chunks, iterations);
        long chunkEvictNanos = timeChunkCentricEviction(chunkCentric, structures, chunks, iterations);

        System.out.println("StructureCheck cache layout benchmark");
        System.out.println("warmup=" + warmup + ", iterations=" + iterations + ", structures=256, chunks=384");
        printMetric("lookup", structureLookupNanos, chunkLookupNanos, iterations);
        printMetric("evict", structureEvictNanos, chunkEvictNanos, iterations);
    }

    private static long timeStructureCentricLookup(
            Map<String, Long2BooleanMap> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        long started = System.nanoTime();
        runStructureCentricLookup(cache, structures, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runStructureCentricLookup(
            Map<String, Long2BooleanMap> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (String structure : structures) {
                local += cache.get(structure).get(chunk) ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeChunkCentricLookup(
            ChunkKeyedBooleanCache<String> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        long started = System.nanoTime();
        runChunkCentricLookup(cache, structures, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runChunkCentricLookup(
            ChunkKeyedBooleanCache<String> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (String structure : structures) {
                local += cache.getOrCompute(chunk, structure, () -> {
                    throw new AssertionError("unexpected cache miss");
                }) ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeStructureCentricEviction(
            Map<String, Long2BooleanMap> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        long started = System.nanoTime();
        runStructureCentricEviction(cache, structures, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runStructureCentricEviction(
            Map<String, Long2BooleanMap> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            for (String structure : structures) {
                cache.get(structure).remove(chunk);
            }
            for (String structure : structures) {
                cache.get(structure).put(chunk, ((chunk ^ System.identityHashCode(structure)) & 1L) == 0L);
                local += cache.get(structure).get(chunk) ? 1 : 0;
            }
        }
        sink = local;
    }

    private static long timeChunkCentricEviction(
            ChunkKeyedBooleanCache<String> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        long started = System.nanoTime();
        runChunkCentricEviction(cache, structures, chunks, iterations);
        return System.nanoTime() - started;
    }

    private static void runChunkCentricEviction(
            ChunkKeyedBooleanCache<String> cache,
            String[] structures,
            long[] chunks,
            int iterations
    ) {
        int local = sink;
        for (int i = 0; i < iterations; i++) {
            long chunk = chunks[i % chunks.length];
            cache.removeChunk(chunk);
            for (String structure : structures) {
                boolean value = cache.getOrCompute(chunk, structure,
                        () -> ((chunk ^ System.identityHashCode(structure)) & 1L) == 0L);
                local += value ? 1 : 0;
            }
        }
        sink = local;
    }

    private static Map<String, Long2BooleanMap> structureCentric(String[] structures, long[] chunks) {
        Map<String, Long2BooleanMap> cache = new HashMap<>(structures.length);
        for (String structure : structures) {
            Long2BooleanOpenHashMap checks = new Long2BooleanOpenHashMap(chunks.length);
            for (long chunk : chunks) {
                checks.put(chunk, ((chunk ^ System.identityHashCode(structure)) & 1L) == 0L);
            }
            cache.put(structure, checks);
        }
        return cache;
    }

    private static ChunkKeyedBooleanCache<String> chunkCentric(String[] structures, long[] chunks) {
        ChunkKeyedBooleanCache<String> cache = new ChunkKeyedBooleanCache<>();
        for (long chunk : chunks) {
            for (String structure : structures) {
                cache.getOrCompute(chunk, structure, () -> ((chunk ^ System.identityHashCode(structure)) & 1L) == 0L);
            }
        }
        return cache;
    }

    private static long[] chunks(int count) {
        long[] chunks = new long[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = ((long) (i * 31) << 32) ^ (i * 17L + 13L);
        }
        return chunks;
    }

    private static String[] structures(int count) {
        String[] structures = new String[count];
        for (int i = 0; i < count; i++) {
            structures[i] = "structure-" + i;
        }
        return structures;
    }

    private static void printMetric(String label, long previousNanos, long nextNanos, int iterations) {
        double previousNsOp = (double) previousNanos / iterations;
        double nextNsOp = (double) nextNanos / iterations;
        double speedup = previousNsOp / Math.max(1.0D, nextNsOp);
        System.out.printf(
                "%s structure-centric=%.1f ns/op chunk-centric=%.1f ns/op speedup=%.2fx%n",
                label,
                previousNsOp,
                nextNsOp,
                speedup
        );
    }
}
