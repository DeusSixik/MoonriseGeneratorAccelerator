package dev.sixik.generator_accelerator.common.features.mixin.features;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = MultifaceBlock.class, priority = 999)
public abstract class MixinMultifaceBlock {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$ATTACHMENT_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    protected abstract boolean isFaceSupported(Direction direction);

    @Shadow
    public static boolean hasFace(BlockState state, Direction direction) {
        throw new RuntimeException();
    }

    @Shadow
    public static boolean canAttachTo(BlockGetter level, Direction direction, BlockPos pos, BlockState state) {
        throw new RuntimeException();
    }

    /**
     * @author Sixik
     * @reason Avoid Arrays.stream/Predicate allocation in sculk vein worldgen updates.
     */
    @Overwrite
    protected static boolean hasAnyFace(BlockState state) {
        return hasFace(state, Direction.DOWN)
                || hasFace(state, Direction.UP)
                || hasFace(state, Direction.NORTH)
                || hasFace(state, Direction.SOUTH)
                || hasFace(state, Direction.WEST)
                || hasFace(state, Direction.EAST);
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos.relative allocation in sculk/glow-lichen spread probes.
     */
    @Overwrite
    public boolean isValidStateForPlacement(BlockGetter level, BlockState state, BlockPos pos, Direction direction) {
        if (!this.isFaceSupported(direction) || state.is((Block) (Object) this) && hasFace(state, direction)) {
            return false;
        }
        BlockPos.MutableBlockPos attachmentPos = GA$ATTACHMENT_POS.get().setWithOffset(pos, direction);
        return canAttachTo(level, direction, attachmentPos, level.getBlockState(attachmentPos));
    }
}
