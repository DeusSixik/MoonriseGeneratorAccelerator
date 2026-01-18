package dev.sixik.moonrisegeneratoraccelerator.common.level.util;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;

public interface MultipointArrayManager<C, I extends ToFloatFunction<C>> {

    CubicSpline<C, I>[] bts$getSplineArray();

    void bts$setSplineArray(CubicSpline<C, I>[] array);
}
