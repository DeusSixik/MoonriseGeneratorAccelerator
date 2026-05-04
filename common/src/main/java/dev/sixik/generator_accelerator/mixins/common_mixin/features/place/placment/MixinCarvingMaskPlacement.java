package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.CarvingMaskPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CarvingMaskPlacement.class)
public abstract class MixinCarvingMaskPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private GenerationStep.Carving step;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int bx = BlockPos.getX(packedPos);
        int bz = BlockPos.getZ(packedPos);
        ChunkPos chunkPos = new ChunkPos(bx >> 4, bz >> 4);

        CarvingMask mask = context.getCarvingMask(chunkPos, this.step);
        if (mask != null) {
            ((GA$CarvingMaskExtension) mask).bts$addPositionsFast(chunkPos, output);
        }
    }
}
