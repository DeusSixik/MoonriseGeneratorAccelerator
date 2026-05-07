package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.google.common.collect.ImmutableList;
import dev.sixik.generator_accelerator.api.patches.GA$StructureManagerExtension;
import dev.sixik.generator_accelerator.common.structures.StructureStartCollector;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

@Mixin(value = StructureManager.class, priority = 999)
public abstract class MixinStructureManager implements GA$StructureManagerExtension {
    @Unique
    private static final ThreadLocal<StructureStartCollector> GA$START_COLLECTOR =
            ThreadLocal.withInitial(StructureStartCollector::new);

    @Shadow
    @Final
    private LevelAccessor level;

    @Shadow
    public abstract void fillStartsForStructure(Structure arg, LongSet longSet, Consumer<StructureStart> consumer);

    /**
     * @author Sixik
     * @reason Redirect {@link ImmutableList.Builder} to {@link ObjectArrayList}
     */
    @Overwrite
    public List<StructureStart> startsForStructure(SectionPos sectionPos, Structure structure) {
        ObjectArrayList<StructureStart> starts = this.ga$startsForStructureFast(sectionPos, structure);
        return starts == null ? new ObjectArrayList<>(0) : starts;
    }

    @Override
    @Nullable
    public ObjectArrayList<StructureStart> ga$startsForStructureFast(SectionPos sectionPos, Structure structure) {
        LongSet longSet = this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES).getReferencesForStructure(structure);
        if (longSet.isEmpty()) {
            return null;
        }
        ObjectArrayList<StructureStart> fastList = new ObjectArrayList<>(longSet.size());
        StructureStartCollector collector = GA$START_COLLECTOR.get();
        collector.bind(fastList);
        try {
            this.fillStartsForStructure(structure, longSet, collector);
        } finally {
            collector.clear();
        }
        return fastList;
    }
}
