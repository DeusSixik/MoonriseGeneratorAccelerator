package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StateTestingPredicate.class)
public abstract class MixinStateTestingPredicate implements GA$BlockPredicateExtension {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$OFFSET_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    protected Vec3i offset;

    @Shadow
    protected abstract boolean test(BlockState blockState);

    @Unique
    private boolean ga$zeroOffset;
    @Unique
    private int ga$offsetX;
    @Unique
    private int ga$offsetY;
    @Unique
    private int ga$offsetZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheOffset(Vec3i offset, CallbackInfo ci) {
        this.ga$offsetX = offset.getX();
        this.ga$offsetY = offset.getY();
        this.ga$offsetZ = offset.getZ();
        this.ga$zeroOffset = this.ga$offsetX == 0 && this.ga$offsetY == 0 && this.ga$offsetZ == 0;
    }

    @Override
    public final boolean ga$testRaw(WorldGenLevel worldGenLevel, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        if (this.ga$zeroOffset) {
            return this.test(worldGenLevel.getBlockState(scratch.set(x, y, z)));
        }

        scratch.set(x + this.ga$offsetX, y + this.ga$offsetY, z + this.ga$offsetZ);
        return this.test(worldGenLevel.getBlockState(scratch));
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos allocation from BlockPos#offset in hot placement predicates.
     */
    @Overwrite
    public final boolean test(WorldGenLevel worldGenLevel, BlockPos blockPos) {
        if (this.ga$zeroOffset) {
            return this.test(worldGenLevel.getBlockState(blockPos));
        }

        BlockPos.MutableBlockPos mutablePos = GA$OFFSET_POS.get();
        mutablePos.set(blockPos.getX() + this.ga$offsetX, blockPos.getY() + this.ga$offsetY, blockPos.getZ() + this.ga$offsetZ);
        return this.test(worldGenLevel.getBlockState(mutablePos));
    }
}
