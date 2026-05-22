package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.DfcCompiledFillArray;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DensityFunctions.RangeChoice.class)
public abstract class RangeChoiceFillArrayMixin implements DensityFunction {
    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void dfc$fillArrayWithCompiled(double[] values, ContextProvider contextProvider, CallbackInfo ci) {
        if (DfcCompiledFillArray.tryFillArray(this, values, contextProvider)) {
            ci.cancel();
        }
    }
}
