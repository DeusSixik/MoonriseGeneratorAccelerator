package dev.sixik.generator_accelerator.common.features.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SharedWeakCacheTest {

    @Test
    void getOrComputeCachesValueForSameKey() {
        SharedWeakCache<Object, String> cache = new SharedWeakCache<>();
        AtomicInteger calls = new AtomicInteger();
        Object key = new Object();

        String first = cache.getOrCompute(key, ignored -> {
            calls.incrementAndGet();
            return "value";
        });
        String second = cache.getOrCompute(key, ignored -> {
            calls.incrementAndGet();
            return "other";
        });

        assertEquals(1, calls.get());
        assertSame(first, second);
        assertEquals(1, cache.size());
    }

    @Test
    void clearAllResetsRegisteredCaches() {
        SharedWeakCache<Object, String> first = new SharedWeakCache<>();
        SharedWeakCache<Object, String> second = new SharedWeakCache<>();

        first.getOrCompute(new Object(), ignored -> "one");
        second.getOrCompute(new Object(), ignored -> "two");

        SharedWeakCache.clearAll();

        assertEquals(0, first.size());
        assertEquals(0, second.size());
    }
}
