package dev.sixik.generator_accelerator.common.features.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

public final class SharedWeakCache<K, V> {
    private static final Set<SharedWeakCache<?, ?>> INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private final Cache<K, V> cache = Caffeine.newBuilder()
            .initialCapacity(16)
            .weakKeys()
            .build();
    private volatile K lastKey;
    private volatile V lastValue;

    public SharedWeakCache() {
        synchronized (INSTANCES) {
            INSTANCES.add(this);
        }
    }

    public V getOrCompute(K key, Function<? super K, ? extends V> factory) {
        K cachedKey = this.lastKey;
        V cachedValue = this.lastValue;
        if (cachedKey == key && cachedValue != null) {
            return cachedValue;
        }

        V cached = this.cache.getIfPresent(key);
        if (cached != null) {
            this.storeLast(key, cached);
            return cached;
        }

        cached = this.cache.get(key, factory);
        this.storeLast(key, cached);
        return cached;
    }

    public int size() {
        return (int) this.cache.estimatedSize();
    }

    public void clear() {
        this.cache.invalidateAll();
        this.cache.cleanUp();
        this.lastKey = null;
        this.lastValue = null;
    }

    public static void clearAll() {
        ArrayList<SharedWeakCache<?, ?>> snapshot;
        synchronized (INSTANCES) {
            snapshot = new ArrayList<>(INSTANCES);
        }
        for (SharedWeakCache<?, ?> cache : snapshot) {
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private void storeLast(K key, V value) {
        if (this.lastKey == key && this.lastValue == value) {
            return;
        }
        this.lastKey = key;
        this.lastValue = value;
    }
}
