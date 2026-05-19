package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
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
        Object2BooleanMap<K> chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks == null || !chunkChecks.containsKey(key)) {
            return null;
        }
        return chunkChecks.getBoolean(key);
    }

    public boolean getOrCompute(long chunkKey, K key, BooleanSupplier computer) {
        Object2BooleanOpenHashMap<K> chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks != null && chunkChecks.containsKey(key)) {
            return chunkChecks.getBoolean(key);
        }

        boolean computed = computer.getAsBoolean();
        chunkChecks = this.checksByChunk.get(chunkKey);
        if (chunkChecks == null) {
            chunkChecks = new Object2BooleanOpenHashMap<>();
            this.checksByChunk.put(chunkKey, chunkChecks);
        }
        if (chunkChecks.containsKey(key)) {
            return chunkChecks.getBoolean(key);
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
