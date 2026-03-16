package dev.sixik.generator_accelerator.common.levelgen.density_custom.pure;

import dev.sixik.generator_accelerator.common.levelgen.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastHalfNegativeDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastHalfNegativeDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastHalfNegativeDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.HALF_NEGATIVE, densityFunction), FastHalfNegativeDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        double value = input.compute(functionContext);
        return value > 0.0 ? value : value * 0.5;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {

            double value = ds[i];
            if (value <= 0.0) {
                ds[i] = value * 0.5;
            }
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastHalfNegativeDensityFunction(input.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
