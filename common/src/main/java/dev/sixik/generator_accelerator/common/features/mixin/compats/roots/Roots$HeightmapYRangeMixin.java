package dev.sixik.generator_accelerator.common.features.mixin.compats.roots;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import mysticmods.roots.worldgen.features.placements.HeightmapYRange;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(HeightmapYRange.class)
public abstract class Roots$HeightmapYRangeMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private Heightmap.Types heightmapToUse;

    @Shadow
    @Final
    private HeightProvider minHeightProvider;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);

        int heightmapY = context.getHeight(this.heightmapToUse, x, z);
        int minY = this.minHeightProvider.sample(random, context);
        int diff = heightmapY - minY;
        if (diff < 1) return;

        int chosenDiff = random.nextInt(diff);
        int chosenFinalY = minY + chosenDiff;

        output.add(BlockPos.asLong(x, chosenFinalY, z));
    }
}
