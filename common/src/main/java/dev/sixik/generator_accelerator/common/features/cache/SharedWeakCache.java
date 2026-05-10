package dev.sixik.generator_accelerator.common.features.cache;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

public final class SharedWeakCache<K, V> {
    private final Map<K, V> cache = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile K lastKey;
    private volatile V lastValue;

    public V getOrCompute(K key, Function<? super K, ? extends V> factory) {
        K cachedKey = this.lastKey;
        V cachedValue = this.lastValue;
        if (cachedKey == key && cachedValue != null) {
            return cachedValue;
        }

        synchronized (this.cache) {
            V cached = this.cache.get(key);
            if (cached != null) {
                this.lastKey = key;
                this.lastValue = cached;
                return cached;
            }
        }

        V computed = factory.apply(key);
        synchronized (this.cache) {
            V cached = this.cache.get(key);
            if (cached == null) {
                cached = computed;
                this.cache.put(key, cached);
            }
            this.lastKey = key;
            this.lastValue = cached;
            return cached;
        }
    }

    public int size() {
        synchronized (this.cache) {
            return this.cache.size();
        }
    }

    public void clear() {
        synchronized (this.cache) {
            this.cache.clear();
        }
        this.lastKey = null;
        this.lastValue = null;
    }
}
