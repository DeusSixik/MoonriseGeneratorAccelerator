package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(StateTestingPredicate.class)
public abstract class MixinStateTestingPredicate {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$OFFSET_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    protected Vec3i offset;

    @Shadow
    protected abstract boolean test(BlockState blockState);

    /**
     * @author Sixik
     * @reason Avoid BlockPos allocation from BlockPos#offset in hot placement predicates.
     */
    @Overwrite
    public final boolean test(WorldGenLevel worldGenLevel, BlockPos blockPos) {
        Vec3i localOffset = this.offset;
        if (localOffset == Vec3i.ZERO) {
            return this.test(worldGenLevel.getBlockState(blockPos));
        }

        BlockPos.MutableBlockPos mutablePos = GA$OFFSET_POS.get();
        mutablePos.set(
                blockPos.getX() + localOffset.getX(),
                blockPos.getY() + localOffset.getY(),
                blockPos.getZ() + localOffset.getZ()
        );
        return this.test(worldGenLevel.getBlockState(mutablePos));
    }
}
