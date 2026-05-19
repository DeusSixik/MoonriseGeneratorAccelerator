package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$StructureManagerExtension;
import dev.sixik.generator_accelerator.common.structures.StructureStartCollector;
import dev.sixik.generator_accelerator.common.structures.StructureStartReferenceFanout;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Mixin(value = StructureManager.class, priority = 999)
public abstract class MixinStructureManager implements GA$StructureManagerExtension {
    @Unique
    private static final ThreadLocal<StructureStartCollector> GA$START_COLLECTOR =
            ThreadLocal.withInitial(StructureStartCollector::new);

    @Shadow
    @Final
    private LevelAccessor level;

    /**
     * @author Sixik
     * @reason Replace ImmutableList.Builder fan-out with an allocation-light ObjectArrayList path.
     */
    @Overwrite
    public List<StructureStart> startsForStructure(ChunkPos chunkPos, Predicate<Structure> predicate) {
        Map<Structure, LongSet> references = this.level
                .getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_REFERENCES)
                .getAllReferences();
        if (references.isEmpty()) {
            return new ObjectArrayList<>(0);
        }

        ObjectArrayList<StructureStart> starts = new ObjectArrayList<>();
        StructureStartCollector collector = GA$START_COLLECTOR.get();
        collector.bind(starts);
        try {
            for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
                Structure structure = entry.getKey();
                if (!predicate.test(structure)) {
                    continue;
                }
                StructureStartReferenceFanout.fillStarts(this.level, structure, entry.getValue(), collector);
            }
        } finally {
            collector.clear();
        }
        return starts.isEmpty() ? new ObjectArrayList<>(0) : starts;
    }

    /**
     * @author Sixik
     * @reason Keep structure-reference lookups on primitive chunk coordinates and a reusable collector.
     */
    @Overwrite
    public List<StructureStart> startsForStructure(SectionPos sectionPos, Structure structure) {
        ObjectArrayList<StructureStart> starts = this.ga$startsForStructureFast(sectionPos, structure);
        return starts == null ? new ObjectArrayList<>(0) : starts;
    }

    @Override
    @Nullable
    public ObjectArrayList<StructureStart> ga$startsForStructureFast(SectionPos sectionPos, Structure structure) {
        LongSet references = this.level
                .getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES)
                .getReferencesForStructure(structure);
        if (references.isEmpty()) {
            return null;
        }

        ObjectArrayList<StructureStart> starts = new ObjectArrayList<>(references.size());
        StructureStartCollector collector = GA$START_COLLECTOR.get();
        collector.bind(starts);
        try {
            StructureStartReferenceFanout.fillStarts(this.level, structure, references, collector);
        } finally {
            collector.clear();
        }
        return starts;
    }

    /**
     * @author Sixik
     * @reason Eliminate per-reference ChunkPos/SectionPos boxing on the structure-start fan-out path.
     */
    @Overwrite
    public void fillStartsForStructure(Structure structure, LongSet references, Consumer<StructureStart> consumer) {
        StructureStartReferenceFanout.fillStarts(this.level, structure, references, consumer);
    }
}
