package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskPlacementAccess;
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
public abstract class MixinCarvingMaskPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$CarvingMaskPlacementAccess {

    @Shadow
    @Final
    private GenerationStep.Carving step;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public GenerationStep.Carving ga$carvingStep() {
        return this.step;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int bx = BlockPos.getX(packedPos);
        int bz = BlockPos.getZ(packedPos);
        ChunkPos chunkPos = new ChunkPos(bx >> 4, bz >> 4);

        CarvingMask mask = context.getCarvingMask(chunkPos, this.step);
        if (mask != null) {
            ((GA$CarvingMaskExtension) mask).bts$addPositionsRaw(chunkPos, output);
        }
    }
}
