package dev.sixik.generator_accelerator.common.density.density_custom.pure;

import dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastSquareDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastSquareDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastSquareDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.SQUARE, densityFunction), FastSquareDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        double value = input.compute(functionContext);
        return value * value;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            ds[i] *= ds[i];
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new FastSquareDensityFunction(input.mapAll(visitor), minValue, maxValue));
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
