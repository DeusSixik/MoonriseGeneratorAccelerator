package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EnvironmentScanPlacement.class)
public abstract class MixinEnvironmentScanPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private BlockPredicate allowedSearchCondition;

    @Shadow
    @Final
    private Direction directionOfSearch;

    @Shadow
    @Final
    private int maxSteps;

    @Shadow
    @Final
    private BlockPredicate targetCondition;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos(x, y, z);
        WorldGenLevel worldgenlevel = context.getLevel();

        if (!this.allowedSearchCondition.test(worldgenlevel, mPos)) {
            return;
        }

        int stepX = this.directionOfSearch.getStepX();
        int stepY = this.directionOfSearch.getStepY();
        int stepZ = this.directionOfSearch.getStepZ();

        for (int i = 0; i < this.maxSteps; i++) {
            if (this.targetCondition.test(worldgenlevel, mPos)) {
                output.add(mPos.asLong());
                return;
            }

            x += stepX;
            y += stepY;
            z += stepZ;
            mPos.set(x, y, z);

            if (worldgenlevel.isOutsideBuildHeight(y)) {
                return;
            }

            if (!this.allowedSearchCondition.test(worldgenlevel, mPos)) {
                break;
            }
        }

        if (this.targetCondition.test(worldgenlevel, mPos)) {
            output.add(mPos.asLong());
        }
    }
}
