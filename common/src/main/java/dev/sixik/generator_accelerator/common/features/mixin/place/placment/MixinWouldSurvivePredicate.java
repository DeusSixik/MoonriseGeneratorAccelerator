package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
import dev.sixik.generator_accelerator.common.features.predicate.GAStatefulPredicateScratch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate")
public abstract class MixinWouldSurvivePredicate implements GA$BlockPredicateExtension {
    @Shadow
    @Final
    private Vec3i offset;
    @Shadow
    @Final
    private BlockState state;

    @Unique
    private int ga$offsetX;
    @Unique
    private int ga$offsetY;
    @Unique
    private int ga$offsetZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheOffset(Vec3i offset, BlockState state, CallbackInfo ci) {
        this.ga$offsetX = offset.getX();
        this.ga$offsetY = offset.getY();
        this.ga$offsetZ = offset.getZ();
    }

    @Override
    public boolean ga$testRaw(WorldGenLevel level, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        scratch.set(x + this.ga$offsetX, y + this.ga$offsetY, z + this.ga$offsetZ);
        return this.state.canSurvive(level, scratch);
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos#offset allocation before BlockState#canSurvive.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mutablePos = GAStatefulPredicateScratch.POS.get();
        mutablePos.set(pos.getX() + this.ga$offsetX, pos.getY() + this.ga$offsetY, pos.getZ() + this.ga$offsetZ);
        return this.state.canSurvive(level, mutablePos);
    }
}
