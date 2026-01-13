package dev.sixik.density_compiller.compiler.wrappers;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record DensityFunctionSplineWrapper(
        CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate> spline)
        implements DensityFunction
{

//    private static final Codec<CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate>> SPLINE_CODEC;
//    private static final MapCodec<DensityFunctionSplineWrapper> DATA_CODEC;
//    public static final KeyDispatchDataCodec<DensityFunctionSplineWrapper> CODEC;

    public double compute(DensityFunction.FunctionContext functionContext) {
        return this.spline.apply(new DensityFunctions.Spline.Point(functionContext));
    }

    public double minValue() {
        return this.spline.minValue();
    }

    public double maxValue() {
        return this.spline.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null;
    }

    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(ds, this);
    }

    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new DensityFunctionSplineWrapper(this.spline.mapAll((coordinate) -> coordinate.mapAll(visitor))));
    }

//    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
//        return CODEC;
//    }

    static {
//        SPLINE_CODEC = CubicSpline.codec(DensityFunctions.Spline.Coordinate.CODEC);
//        DATA_CODEC = SPLINE_CODEC.fieldOf("spline").xmap(DensityFunctionSplineWrapper::new, DensityFunctionSplineWrapper::spline);
//        CODEC = DensityFunctions.<DensityFunctionSplineWrapper>makeCodec(DATA_CODEC);
    }
}
