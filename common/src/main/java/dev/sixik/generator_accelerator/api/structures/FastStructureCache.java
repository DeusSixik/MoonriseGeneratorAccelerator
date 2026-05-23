package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.api.patches.GA$StructureExtension;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.structure.Structure;

public final class FastStructureCache {
    private static volatile boolean initialized;
    private static int size;
    private static Reference2IntOpenHashMap<Structure> IDS_BY_STRUCTURE;

    public static Holder<Structure>[] STRUCTURES;
    public static Structure[] STRUCTURES_VALUE;

    private FastStructureCache() {
    }

    public static void init(Registry<Structure> registry) {
        int registrySize = registry.size();
        if (registrySize <= 0) {
            return;
        }

        if (initialized && size == registrySize && STRUCTURES_VALUE != null && IDS_BY_STRUCTURE != null) {
            return;
        }

        synchronized (FastStructureCache.class) {
            registrySize = registry.size();
            if (registrySize <= 0) {
                return;
            }

            if (initialized && size == registrySize && STRUCTURES_VALUE != null && IDS_BY_STRUCTURE != null) {
                return;
            }

            Structure[] structures = new Structure[registrySize];
            Holder<Structure>[] structuresHolder = new Holder[registrySize];
            Reference2IntOpenHashMap<Structure> idsByStructure = new Reference2IntOpenHashMap<>(registrySize);
            idsByStructure.defaultReturnValue(-1);

            int nextId = 0;
            for (Structure structure : registry) {
                structures[nextId] = structure;
                structuresHolder[nextId] = registry.wrapAsHolder(structure);
                idsByStructure.put(structure, nextId);
                GA$StructureExtension.get(structure).bts$setFastId(nextId);
                nextId++;
            }

            STRUCTURES = structuresHolder;
            STRUCTURES_VALUE = structures;
            IDS_BY_STRUCTURE = idsByStructure;
            size = nextId;
            initialized = true;
        }
    }

    public static int getId(Structure structure) {
        if (structure == null) {
            return -1;
        }

        Reference2IntOpenHashMap<Structure> ids = IDS_BY_STRUCTURE;
        if (ids == null) {
            throw new IllegalStateException("FastStructureCache is not initialized");
        }
        return ids.getInt(structure);
    }

    public static Structure getStructure(int id) {
        Structure[] structures = STRUCTURES_VALUE;
        if (structures == null || id < 0 || id >= structures.length) {
            return null;
        }
        return structures[id];
    }

    public static int size() {
        return size;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
