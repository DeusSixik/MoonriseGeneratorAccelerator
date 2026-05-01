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

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Mixin(value = StructureManager.class, priority = 4000)
public abstract class MixinStructureManager {

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
        ObjectArrayList<StructureStart> fastList = new ObjectArrayList<>();
        Objects.requireNonNull(fastList);
        this.fillStartsForStructure(structure, longSet, fastList::add);
        return fastList;
    }
}
