package dev.sixik.moonrisegeneratoraccelerator.common.mixin.util;

import dev.sixik.moonrisegeneratoraccelerator.common.level.util.MultipointArrayManager;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.util.ToFloatFunction;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

@Mixin(CubicSpline.Multipoint.class)
public abstract class MixinMultipoint<C, I extends ToFloatFunction<C>>
        implements CubicSpline<C, I>, MultipointArrayManager<C, I> {

    @Shadow
    @Final
    private I coordinate;
    @Shadow
    @Final
    private float[] locations;

    @Shadow
    private static float linearExtend(float f, float[] fs, float g, float[] gs, int i) {
        throw new RuntimeException();
    }

    @Shadow
    private static int findIntervalStart(float[] fs, float f) {
        throw new RuntimeException();
    }

    @Shadow
    @Final
    private float[] derivatives;
    @Unique
    private CubicSpline<C, I>[] bts$array;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(
            I coordinate,
            float[] locations,
            List<CubicSpline<C, I>> values,
            float[] derivatives,
            float minValue,
            float maxValue,
            CallbackInfo ci
    ) {
        final var size = values.size();

        bts$array = new CubicSpline[size];
        for (int i = 0; i < values.size(); i++) {
            bts$array[i] = values.get(i);
        }

    }


    @Override
    public CubicSpline<C, I>[] bts$getSplineArray() {
        return bts$array;
    }

    @Override
    public void bts$setSplineArray(CubicSpline<C, I>[] array) {
        this.bts$array = array;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public float apply(C object) {
        final var bts$array = this.bts$array;
        final var locations = this.locations;
        final var derivatives = this.derivatives;

        float f = this.coordinate.apply(object);
        int i = findIntervalStart(locations, f);
        int j = locations.length - 1;
        if (i < 0) {
            return linearExtend(f, locations, bts$array[0].apply(object), derivatives, 0);
        }
        if (i == j) {
            return linearExtend(f, locations, bts$array[j].apply(object), derivatives, j);
        }
        float g = locations[i];
        float h = locations[i + 1];
        float k = (f - g) / (h - g);
        float l = derivatives[i];
        float m = derivatives[i + 1];
        float n = bts$array[i].apply(object);
        float o = bts$array[i + 1].apply(object);
        float p = l * (h - g) - (o - n);
        float q = -m * (h - g) + (o - n);
        return Mth.lerp(k, n, o) + k * (1.0f - k) * Mth.lerp(k, p, q);
    }

    @Unique
    private static int bts$findIntervalStart(float[] fs, float f) {
        return Arrays.binarySearch(fs, f);
    }
}
