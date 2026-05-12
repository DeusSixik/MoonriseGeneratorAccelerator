package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.InsideWorldBoundsPredicate")
public abstract class MixinInsideWorldBoundsPredicate implements GA$BlockPredicateExtension {
    @Shadow
    @Final
    private Vec3i offset;

    @Unique
    private int ga$offsetY;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ga$cacheOffset(Vec3i offset, CallbackInfo ci) {
        this.ga$offsetY = offset.getY();
    }

    @Override
    public boolean ga$testRaw(WorldGenLevel level, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        return !level.isOutsideBuildHeight(y + this.ga$offsetY);
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos#offset allocation for pure height-bound checks.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos.getY() + this.ga$offsetY);
    }
}
