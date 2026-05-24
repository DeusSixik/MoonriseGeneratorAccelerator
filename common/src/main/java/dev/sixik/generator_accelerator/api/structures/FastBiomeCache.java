package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.api.patches.GA$BiomeExtension;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

public final class FastBiomeCache {
    private static volatile boolean initialized;
    private static int size;
    private static Reference2IntOpenHashMap<Biome> IDS_BY_BIOME;
    private static Object2IntOpenHashMap<Holder<Biome>> IDS_BY_HOLDER;

    public static Holder<Biome>[] BIOMES;
    public static Biome[] BIOME_VALUES;

    private FastBiomeCache() {
    }

    public static void init(Registry<Biome> registry) {
        int registrySize = registry.size();
        if (registrySize <= 0) {
            return;
        }

        if (initialized && size == registrySize && BIOMES != null && BIOME_VALUES != null
                && IDS_BY_BIOME != null && IDS_BY_HOLDER != null) {
            return;
        }

        synchronized (FastBiomeCache.class) {
            registrySize = registry.size();
            if (registrySize <= 0) {
                return;
            }

            if (initialized && size == registrySize && BIOMES != null && BIOME_VALUES != null
                    && IDS_BY_BIOME != null && IDS_BY_HOLDER != null) {
                return;
            }

            @SuppressWarnings("unchecked")
            Holder<Biome>[] holders = (Holder<Biome>[]) new Holder<?>[registrySize];
            Biome[] values = new Biome[registrySize];
            Reference2IntOpenHashMap<Biome> idsByBiome = new Reference2IntOpenHashMap<>(registrySize);
            Object2IntOpenHashMap<Holder<Biome>> idsByHolder = new Object2IntOpenHashMap<>(registrySize);
            idsByBiome.defaultReturnValue(-1);
            idsByHolder.defaultReturnValue(-1);

            int nextId = 0;
            for (Biome biome : registry) {
                Holder<Biome> holder = registry.wrapAsHolder(biome);
                holders[nextId] = holder;
                values[nextId] = biome;
                idsByBiome.put(biome, nextId);
                idsByHolder.put(holder, nextId);
                GA$BiomeExtension.get(biome).bts$setFastId(nextId);
                nextId++;
            }

            BIOMES = holders;
            BIOME_VALUES = values;
            IDS_BY_BIOME = idsByBiome;
            IDS_BY_HOLDER = idsByHolder;
            size = nextId;
            initialized = true;
        }
    }

    public static int getId(Biome biome) {
        if (biome == null) {
            return -1;
        }

        Reference2IntOpenHashMap<Biome> ids = IDS_BY_BIOME;
        if (ids == null) {
            throw new IllegalStateException("FastBiomeCache is not initialized");
        }
        return ids.getInt(biome);
    }

    public static int getId(Holder<Biome> biome) {
        if (biome == null) {
            return -1;
        }

        Object2IntOpenHashMap<Holder<Biome>> ids = IDS_BY_HOLDER;
        if (ids == null) {
            throw new IllegalStateException("FastBiomeCache is not initialized");
        }
        return ids.getInt(biome);
    }

    public static Holder<Biome> getBiomeHolder(int id) {
        Holder<Biome>[] holders = BIOMES;
        if (holders == null || id < 0 || id >= holders.length) {
            return null;
        }
        return holders[id];
    }

    public static Biome getBiome(int id) {
        Biome[] biomes = BIOME_VALUES;
        if (biomes == null || id < 0 || id >= biomes.length) {
            return null;
        }
        return biomes[id];
    }

    public static int size() {
        return size;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
