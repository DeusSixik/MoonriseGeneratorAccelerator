package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Structure-heavy modpacks probe many structures for the same chunk. A
 * chunk-centric cache keeps those lookups and evictions local.
 */
public final class ChunkKeyedBooleanCache<K> {
    private final Long2ObjectOpenHashMap<Object2BooleanOpenHashMap<K>> checksByChunk = new Long2ObjectOpenHashMap<>();

    @Nullable
    public Boolean get(long chunkKey, K key) {
        Object2BooleanOpenHashMap<K> chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks == null) {
            return null;
        }
        boolean cached = chunkChecks.getBoolean(key);
        if (cached || chunkChecks.containsKey(key)) {
            return cached;
        }
        return null;
    }

    public boolean getOrCompute(long chunkKey, K key, BooleanSupplier computer) {
        Object2BooleanOpenHashMap<K> chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks != null) {
            boolean cached = chunkChecks.getBoolean(key);
            if (cached || chunkChecks.containsKey(key)) {
                return cached;
            }
        }

        boolean computed = computer.getAsBoolean();
        chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks == null) {
            chunkChecks = new Object2BooleanOpenHashMap<>(4);
            this.checksByChunk.put(chunkKey, chunkChecks);
        }
        boolean cached = chunkChecks.getBoolean(key);
        if (cached || chunkChecks.containsKey(key)) {
            return cached;
        }

        chunkChecks.put(key, computed);
        return computed;
    }

    public void removeChunk(long chunkKey) {
        this.checksByChunk.remove(chunkKey);
    }

    public void clear() {
        this.checksByChunk.clear();
    }
}
