package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$CountOnEveryLayerPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.NotImplementedException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CountOnEveryLayerPlacement.class)
public abstract class MixinCountOnEveryLayerPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$CountOnEveryLayerPlacementAccess {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    private static boolean isEmpty(BlockState arg) {
        throw new NotImplementedException();
    }

    @Shadow
    @Final
    private IntProvider count;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public IntProvider ga$countProvider() {
        return this.count;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int startX = BlockPos.getX(packedPos);
        int startZ = BlockPos.getZ(packedPos);

        BlockPos.MutableBlockPos mPos = GA$MUTABLE_POS.get();

        int layer = 0;
        boolean foundOnLayer;
        do {
            foundOnLayer = false;

            int samples = this.count.sample(random);
            for (int j = 0; j < samples; j++) {
                int x = random.nextInt(16) + startX;
                int z = random.nextInt(16) + startZ;
                int y = context.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

                int targetY = bts$findOnGroundYPositionFast(context, x, y, z, layer, mPos);
                if (targetY != Integer.MAX_VALUE) {
                    output.add(BlockPos.asLong(x, targetY, z));
                    foundOnLayer = true;
                }
            }

            layer++;
        } while (foundOnLayer);
    }

    @Unique
    private static int bts$findOnGroundYPositionFast(PlacementContext context, int x, int y, int z, int targetCount, BlockPos.MutableBlockPos mPos) {
        mPos.set(x, y, z);
        int currentCount = 0;
        BlockState currentState = context.getBlockState(mPos);

        for (int currentY = y; currentY >= context.getMinBuildHeight() + 1; currentY--) {
            mPos.setY(currentY - 1);
            BlockState nextState = context.getBlockState(mPos);

            if (!isEmpty(nextState) && isEmpty(currentState) && !nextState.is(Blocks.BEDROCK)) {
                if (currentCount == targetCount) {
                    return mPos.getY() + 1;
                }
                currentCount++;
            }
            currentState = nextState;
        }

        return Integer.MAX_VALUE;
    }
}
