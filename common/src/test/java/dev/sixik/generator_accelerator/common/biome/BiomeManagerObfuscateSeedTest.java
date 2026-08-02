package dev.sixik.generator_accelerator.common.biome;

import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeManagerObfuscateSeedTest {
    @Test
    void obfuscateSeedMatchesVanillaHashUnderConcurrentAccess() throws Exception {
        Class<?> mixinClass = Class.forName("dev.sixik.generator_accelerator.common.biome.mixin.MixinBiomeManager$optimize_biome_getter");
        Method obfuscateSeed = mixinClass.getMethod("obfuscateSeed", long.class);
        long[] seeds = {0L, 1L, -1L, Long.MIN_VALUE, 0x1234_5678_9ABC_DEF0L};
        int workers = 8;
        int iterations = 4096;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>(workers);
            for (int worker = 0; worker < workers; worker++) {
                final int workerId = worker;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        long seed = seeds[(i + workerId) % seeds.length];
                        long expected = Hashing.sha256().hashLong(seed).asLong();
                        long actual = (Long) obfuscateSeed.invoke(null, seed);
                        assertEquals(expected, actual, "worker=" + workerId + ", iteration=" + i + ", seed=" + seed);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
