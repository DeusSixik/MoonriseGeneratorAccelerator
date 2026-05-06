package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$RandomOffsetPlacementAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RandomOffsetPlacement.class)
public abstract class MixinRandomOffsetPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$RandomOffsetPlacementAccess {

    @Shadow
    @Final
    private IntProvider xzSpread;

    @Shadow
    @Final
    private IntProvider ySpread;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public IntProvider ga$xzSpread() {
        return this.xzSpread;
    }

    @Override
    public IntProvider ga$ySpread() {
        return this.ySpread;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos) + this.xzSpread.sample(random);
        int y = BlockPos.getY(packedPos) + this.ySpread.sample(random);
        int z = BlockPos.getZ(packedPos) + this.xzSpread.sample(random);

        output.add(BlockPos.asLong(x, y, z));
    }
}
