package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.google.common.collect.ImmutableList;
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

import java.util.List;
import java.util.function.Consumer;

@Mixin(value = StructureManager.class, priority = 999)
public abstract class MixinStructureManager {
    @Unique
    private static final ThreadLocal<GA$StructureStartCollector> GA$START_COLLECTOR =
            ThreadLocal.withInitial(GA$StructureStartCollector::new);

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
        LongSet longSet = this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES).getReferencesForStructure(structure);
        ObjectArrayList<StructureStart> fastList = new ObjectArrayList<>(longSet.size());
        GA$StructureStartCollector collector = GA$START_COLLECTOR.get();
        collector.target = fastList;
        try {
            this.fillStartsForStructure(structure, longSet, collector);
        } finally {
            collector.target = null;
        }
        return fastList;
    }

    @Unique
    private static final class GA$StructureStartCollector implements Consumer<StructureStart> {
        private ObjectArrayList<StructureStart> target;

        @Override
        public void accept(StructureStart structureStart) {
            this.target.add(structureStart);
        }
    }
}
