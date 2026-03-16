package dev.sixik.generator_accelerator.common.levelgen.density;

import com.mojang.datafixers.kinds.K1;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.ToFloatFunction;

public record Point<C, I extends ToFloatFunction<C>>(float location, CubicSpline<C, I> value, float derivative) implements K1 {
}
