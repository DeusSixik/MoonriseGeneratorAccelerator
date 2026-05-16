package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldGenRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SeagrassFeature.class, priority = 999)
public abstract class MixinSeagrassFeature extends Feature<ProbabilityFeatureConfiguration> {

    @Unique
    private static final BlockState GA$SEAGRASS = Blocks.SEAGRASS.defaultBlockState();

    @Unique
    private static final BlockState GA$TALL_SEAGRASS_LOWER = Blocks.TALL_SEAGRASS.defaultBlockState();

    @Unique
    private static final BlockState GA$TALL_SEAGRASS_UPPER =
            GA$TALL_SEAGRASS_LOWER.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$ABOVE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinSeagrassFeature(Codec<ProbabilityFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Remove temporary position/state churn from the single-spot seagrass placement path.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<ProbabilityFeatureConfiguration> placeContext) {
        RandomSource random = placeContext.random();
        WorldGenLevel level = placeContext.level();
        BlockPos origin = placeContext.origin();
        ProbabilityFeatureConfiguration config = placeContext.config();

        int x = origin.getX() + random.nextInt(8) - random.nextInt(8);
        int z = origin.getZ() + random.nextInt(8) - random.nextInt(8);
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        BlockPos.MutableBlockPos pos = GA$POS.get();
        pos.set(x, y, z);

        if (!level.getBlockState(pos).is(Blocks.WATER)) {
            return false;
        }

        boolean tall = random.nextDouble() < config.probability;
        BlockState state = tall ? GA$TALL_SEAGRASS_LOWER : GA$SEAGRASS;
        if (!state.canSurvive(level, pos)) {
            return false;
        }

        if (tall) {
            BlockPos.MutableBlockPos abovePos = GA$ABOVE_POS.get();
            abovePos.setWithOffset(pos, Direction.UP);
            if (!level.getBlockState(abovePos).is(Blocks.WATER)) {
                return false;
            }
            if (!GAWorldGenRegionAccess.canWriteWithoutLogging(level, pos)
                    || !GAWorldGenRegionAccess.canWriteWithoutLogging(level, abovePos)) {
                return false;
            }

            level.setBlock(pos, state, 2);
            level.setBlock(abovePos, GA$TALL_SEAGRASS_UPPER, 2);
            return true;
        }

        if (!GAWorldGenRegionAccess.canWriteWithoutLogging(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, 2);
        return true;
    }
}
