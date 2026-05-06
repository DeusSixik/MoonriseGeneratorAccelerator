package dev.sixik.generator_accelerator.common.features.mixin.compats.roots;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import mysticmods.roots.worldgen.features.placements.AllAroundLogPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collections;
import java.util.List;

@Mixin(AllAroundLogPlacement.class)
public abstract class Roots$AllAroundLogPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private List<Direction> DIRECTIONS;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        Collections.shuffle(this.DIRECTIONS);

        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(x, y, z);

        for(Direction direction : this.DIRECTIONS) {
            if (context.getBlockState(blockPos.move(direction)).isAir()) {
                output.add(blockPos.asLong());
                return;
            }
        }

    }
}
