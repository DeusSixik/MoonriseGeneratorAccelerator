package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(HeightmapPlacement.class)
public abstract class MixinHeightmapPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private Heightmap.Types heightmap;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = context.getHeight(this.heightmap, x, z);

        if (y > context.getMinBuildHeight()) {
            output.add(BlockPos.asLong(x, y, z));
        }
    }
}
