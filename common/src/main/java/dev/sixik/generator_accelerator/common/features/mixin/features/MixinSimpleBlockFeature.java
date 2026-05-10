package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SimpleBlockFeature.class, priority = 999)
public abstract class MixinSimpleBlockFeature extends Feature<SimpleBlockConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$ABOVE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinSimpleBlockFeature(Codec<SimpleBlockConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Reuse the upward scratch position while preserving vanilla placement order and flags.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<SimpleBlockConfiguration> placeContext) {
        SimpleBlockConfiguration config = placeContext.config();
        WorldGenLevel level = placeContext.level();
        BlockPos origin = placeContext.origin();
        BlockState state = config.toPlace().getState(placeContext.random(), origin);

        if (!state.canSurvive(level, origin)) {
            return false;
        }

        if (state.getBlock() instanceof DoublePlantBlock) {
            BlockPos.MutableBlockPos abovePos = GA$ABOVE_POS.get();
            abovePos.setWithOffset(origin, Direction.UP);
            if (!level.isEmptyBlock(abovePos)) {
                return false;
            }

            DoublePlantBlock.placeAt(level, state, origin, 2);
            return true;
        }

        level.setBlock(origin, state, 2);
        return true;
    }
}
