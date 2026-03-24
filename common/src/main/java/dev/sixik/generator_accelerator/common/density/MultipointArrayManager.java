package dev.sixik.generator_accelerator.common.density;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;

public interface MultipointArrayManager<C, I extends ToFloatFunction<C>> {

    CubicSpline<C, I>[] bts$getSplineArray();

    void bts$setSplineArray(CubicSpline<C, I>[] array);
}