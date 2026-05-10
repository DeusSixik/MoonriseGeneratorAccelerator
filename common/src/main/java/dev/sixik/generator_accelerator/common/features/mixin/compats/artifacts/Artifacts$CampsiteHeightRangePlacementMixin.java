package dev.sixik.generator_accelerator.common.features.mixin.compats.artifacts;

import artifacts.Artifacts;
import artifacts.world.placement.CampsiteHeightRangePlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CampsiteHeightRangePlacement.class)
public abstract class Artifacts$CampsiteHeightRangePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    private static int minY;
    private static int maxY;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(CallbackInfo ci) {
        minY = Artifacts.CONFIG.general.campsite.minY.get();
        maxY = Artifacts.CONFIG.general.campsite.maxY.get();
    }

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        if(minY > maxY) return;
        output.add(BlockPos.asLong(BlockPos.getX(packedPos), Mth.randomBetweenInclusive(random, minY, maxY), BlockPos.getZ(packedPos)));
    }
}
