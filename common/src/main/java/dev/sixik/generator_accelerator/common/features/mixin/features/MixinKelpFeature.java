package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = KelpFeature.class, priority = 999)
public abstract class MixinKelpFeature extends Feature<NoneFeatureConfiguration> {

    @Unique
    private static final BlockState GA$KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();

    @Unique
    private static final BlockState[] GA$KELP_HEADS = new BlockState[] {
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 20),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 21),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 22),
            Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 23)
    };

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$ABOVE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$BELOW_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinKelpFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Reuse mutable positions and prebuilt kelp head states instead of allocating new `BlockPos` and property states in the growth loop.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> placeContext) {
        WorldGenLevel level = placeContext.level();
        BlockPos origin = placeContext.origin();
        RandomSource random = placeContext.random();

        int x = origin.getX();
        int z = origin.getZ();
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = GA$POS.get();
        pos.set(x, y, z);

        if (!level.getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }

        BlockPos.MutableBlockPos abovePos = GA$ABOVE_POS.get();
        BlockPos.MutableBlockPos belowPos = GA$BELOW_POS.get();
        int maxHeight = 1 + random.nextInt(10);
        int placedHeads = 0;

        for (int step = 0; step <= maxHeight; step++) {
            abovePos.setWithOffset(pos, Direction.UP);
            if (level.getBlockState(pos).is(Blocks.WATER)
                    && level.getBlockState(abovePos).is(Blocks.WATER)
                    && GA$KELP_PLANT.canSurvive(level, pos)) {
                if (step == maxHeight) {
                    level.setBlock(pos, GA$KELP_HEADS[random.nextInt(4)], 2);
                    placedHeads++;
                    break;
                }

                level.setBlock(pos, GA$KELP_PLANT, 2);
            } else if (step > 0) {
                belowPos.setWithOffset(pos, Direction.DOWN);
                if (GA$KELP_HEADS[0].canSurvive(level, belowPos)) {
                    abovePos.setWithOffset(belowPos, Direction.DOWN);
                    if (!level.getBlockState(abovePos).is(Blocks.KELP)) {
                        level.setBlock(belowPos, GA$KELP_HEADS[random.nextInt(4)], 2);
                        placedHeads++;
                    }
                }
                break;
            } else {
                break;
            }

            pos.move(Direction.UP);
        }

        return placedHeads > 0;
    }
}
