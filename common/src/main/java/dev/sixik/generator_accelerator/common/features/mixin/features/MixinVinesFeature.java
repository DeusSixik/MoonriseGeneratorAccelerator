package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.VinesFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = VinesFeature.class, priority = 999)
public abstract class MixinVinesFeature extends Feature<NoneFeatureConfiguration> {

    @Unique
    private static final Direction[] GA$DIRECTIONS = new Direction[] {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    @Unique
    private static final BlockState[] GA$FACE_STATES = ga$buildFaceStates();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$NEIGHBOR_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinVinesFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Avoid repeated `relative` allocations and per-call vine state construction on this tiny but frequent feature.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> placeContext) {
        WorldGenLevel level = placeContext.level();
        BlockPos origin = placeContext.origin();

        if (!level.isEmptyBlock(origin)) {
            return false;
        }

        BlockPos.MutableBlockPos neighborPos = GA$NEIGHBOR_POS.get();
        for (int i = 0; i < GA$DIRECTIONS.length; i++) {
            Direction direction = GA$DIRECTIONS[i];
            neighborPos.setWithOffset(origin, direction);
            if (VineBlock.isAcceptableNeighbour(level, neighborPos, direction)) {
                level.setBlock(origin, GA$FACE_STATES[direction.ordinal()], 2);
                return true;
            }
        }

        return false;
    }

    @Unique
    private static BlockState[] ga$buildFaceStates() {
        BlockState[] states = new BlockState[Direction.values().length];
        BlockState base = Blocks.VINE.defaultBlockState();
        for (Direction direction : GA$DIRECTIONS) {
            states[direction.ordinal()] = base.setValue(VineBlock.getPropertyForFace(direction), true);
        }
        return states;
    }
}
