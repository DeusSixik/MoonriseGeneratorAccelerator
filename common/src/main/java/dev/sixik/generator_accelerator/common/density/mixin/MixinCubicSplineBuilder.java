package dev.sixik.generator_accelerator.common.density.mixin;

import com.google.common.collect.ImmutableList;
import dev.sixik.generator_accelerator.common.density.density.FastMultipoint;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CubicSpline.Builder.class)
public abstract class MixinCubicSplineBuilder<C, I extends ToFloatFunction<C>> {


    @Shadow
    @Final
    private I coordinate;

    @Shadow
    @Final
    private FloatList locations;

    @Shadow
    @Final
    private List<CubicSpline<C, I>> values;

    @Shadow
    @Final
    private FloatList derivatives;

    @Inject(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CubicSpline$Multipoint;create(Lnet/minecraft/util/ToFloatFunction;[FLjava/util/List;[F)Lnet/minecraft/util/CubicSpline$Multipoint;"), cancellable = true)
    public void bts$build(CallbackInfoReturnable<CubicSpline<C, I>> cir) {
        cir.setReturnValue(FastMultipoint.createFast(
                this.coordinate, this.locations.toFloatArray(), ImmutableList.copyOf(this.values), this.derivatives.toFloatArray()
        ));
    }
}
