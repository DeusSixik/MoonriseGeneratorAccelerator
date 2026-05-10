package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$HeightRangePlacementAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(HeightRangePlacement.class)
public abstract class MixinHeightRangePlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$HeightRangePlacementAccess {

    @Shadow
    @Final
    private HeightProvider height;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public HeightProvider ga$heightProvider() {
        return this.height;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);

        int y = this.height.sample(random, context);

        output.add(BlockPos.asLong(x, y, z));
    }
}
