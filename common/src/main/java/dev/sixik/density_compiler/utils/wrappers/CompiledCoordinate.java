package dev.sixik.density_compiler.utils.wrappers;

import net.minecraft.util.ToFloatFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.jetbrains.annotations.NotNull;

public record CompiledCoordinate(DensityFunction context)
        implements ToFloatFunction<DensityFunctions.Spline.Point> {

//    public static final Codec<CompiledCoordinate> CODEC;

    @Override
    public float apply(DensityFunctions.Spline.Point point) {
        return (float) context.compute(point.context());
    }

    @Override
    public float minValue() {
        return (float) context.minValue();
    }

    @Override
    public float maxValue() {
        return (float) context.maxValue();
    }

    @Override
    public @NotNull String toString() {
        return context.getClass().getName();
    }

    public CompiledCoordinate mapAll(DensityFunction.Visitor visitor) {
        return new CompiledCoordinate(context.mapAll(visitor));
    }


//    static {
//        CODEC = DensityFunction.CODEC.xmap(CompiledCoordinate::new, CompiledCoordinate::context);
//    }
//
}