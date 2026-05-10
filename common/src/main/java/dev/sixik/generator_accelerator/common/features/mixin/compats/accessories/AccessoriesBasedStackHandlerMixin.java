package dev.sixik.generator_accelerator.common.features.mixin.compats.accessories;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "io.wispforest.accessories_compat.curios.wrapper.AccessoriesBasedStackHandler", remap = false)
public abstract class AccessoriesBasedStackHandlerMixin {

    @Unique
    private Object ga$cachedStacksHandler;

    @Unique
    private Object ga$cachedCosmeticStacksHandler;

    @Inject(method = "getStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void ga$getCachedStacks(CallbackInfoReturnable<Object> cir) {
        Object cached = this.ga$cachedStacksHandler;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getStacks", at = @At("RETURN"), remap = false)
    private void ga$storeCachedStacks(CallbackInfoReturnable<Object> cir) {
        if (this.ga$cachedStacksHandler == null) {
            this.ga$cachedStacksHandler = cir.getReturnValue();
        }
    }

    @Inject(method = "getCosmeticStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void ga$getCachedCosmeticStacks(CallbackInfoReturnable<Object> cir) {
        Object cached = this.ga$cachedCosmeticStacksHandler;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getCosmeticStacks", at = @At("RETURN"), remap = false)
    private void ga$storeCachedCosmeticStacks(CallbackInfoReturnable<Object> cir) {
        if (this.ga$cachedCosmeticStacksHandler == null) {
            this.ga$cachedCosmeticStacksHandler = cir.getReturnValue();
        }
    }
}
