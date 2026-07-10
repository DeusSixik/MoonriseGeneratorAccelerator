package dev.sixik.generator_accelerator.common.surface_compiler.cache;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BoundedProgramStore {
    private final int maxEntries;
    private final Map<FingerprintCacheKey, SurfaceExecutionPlan> entries;
    private final Set<FingerprintCacheKey> quarantined = ConcurrentHashMap.newKeySet();

    public BoundedProgramStore(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.entries = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<FingerprintCacheKey, SurfaceExecutionPlan> eldest) {
                return size() > BoundedProgramStore.this.maxEntries;
            }
        };
    }

    public synchronized SurfaceExecutionPlan get(FingerprintCacheKey key) {
        return this.entries.get(key);
    }

    public synchronized void put(FingerprintCacheKey key, SurfaceExecutionPlan plan) {
        this.entries.put(key, plan);
    }

    public void quarantine(FingerprintCacheKey key) {
        this.quarantined.add(key);
        synchronized (this) {
            this.entries.remove(key);
        }
    }

    public boolean isQuarantined(FingerprintCacheKey key) {
        return this.quarantined.contains(key);
    }

    public synchronized int size() {
        return this.entries.size();
    }

    public synchronized void clear() {
        this.entries.clear();
        this.quarantined.clear();
    }
}
