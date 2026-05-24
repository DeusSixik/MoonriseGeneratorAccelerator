package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Collections;
import java.util.Map;

public final class StructureReferenceSnapshot {
    private StructureReferenceSnapshot() {
    }

    public static LongSet copyReferences(LongSet references) {
        if (references == null || references.isEmpty()) {
            return LongSets.EMPTY_SET;
        }
        return LongSets.unmodifiable(new LongOpenHashSet(references));
    }

    public static Map<Structure, LongSet> copyReferences(Map<Structure, LongSet> referencesByStructure) {
        if (referencesByStructure == null || referencesByStructure.isEmpty()) {
            return Collections.emptyMap();
        }

        Reference2ObjectOpenHashMap<Structure, LongSet> copied = new Reference2ObjectOpenHashMap<>(referencesByStructure.size());
        for (Map.Entry<Structure, LongSet> entry : referencesByStructure.entrySet()) {
            copied.put(entry.getKey(), copyReferences(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }
}
