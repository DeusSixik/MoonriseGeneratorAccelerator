package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
public abstract class MixinRandomOffsetPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private IntProvider xzSpread;

    @Shadow
    @Final
    private IntProvider ySpread;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos) + this.xzSpread.sample(random);
        int y = BlockPos.getY(packedPos) + this.ySpread.sample(random);
        int z = BlockPos.getZ(packedPos) + this.xzSpread.sample(random);

        output.add(BlockPos.asLong(x, y, z));
    }
}
