package dev.sixik.generator_accelerator.mixins.common_mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(StructureCheck.class)
public abstract class MixinStructureCheck$lazy_structure_map {

    @Shadow
    private void storeFullResults(long chunkPos, Object2IntMap<Structure> references) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Most generated chunks have no valid structure starts. Vanilla still allocates
     * an Object2IntOpenHashMap for every loaded chunk; keep the shared empty map until the
     * first valid start is actually seen.
     */
    @Overwrite
    public void onStructureLoad(ChunkPos chunkPos, Map<Structure, StructureStart> starts) {
        long packedChunkPos = chunkPos.toLong();
        if (starts.isEmpty()) {
            this.storeFullResults(packedChunkPos, Object2IntMaps.emptyMap());
            return;
        }

        Object2IntOpenHashMap<Structure> references = null;
        for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (!start.isValid()) {
                continue;
            }
            if (references == null) {
                references = new Object2IntOpenHashMap<>(starts.size());
            }
            references.put(entry.getKey(), start.getReferences());
        }

        this.storeFullResults(
                packedChunkPos,
                references == null ? Object2IntMaps.emptyMap() : references
        );
    }
}
