package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$HeightmapPlacementAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
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
public abstract class MixinHeightmapPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$HeightmapPlacementAccess {

    @Shadow
    @Final
    private Heightmap.Types heightmap;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public Heightmap.Types ga$heightmapType() {
        return this.heightmap;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int y = context.getHeight(this.heightmap, x, z);

        if (y > context.getMinBuildHeight()) {
            output.add(BlockPos.asLong(x, y, z));
        }
    }
}
