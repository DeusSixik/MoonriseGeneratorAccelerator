package dev.sixik.generator_accelerator.common.levelgen.density_custom.pure;

import dev.sixik.generator_accelerator.common.levelgen.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastQuarterNegativeDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastQuarterNegativeDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastQuarterNegativeDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.QUARTER_NEGATIVE, densityFunction), FastQuarterNegativeDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        double value = input.compute(functionContext);
        return value > 0.0 ? value : value * 0.25;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            double value = ds[i];
            if (value <= 0.0) {
                ds[i] = value * 0.25;
            }
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastQuarterNegativeDensityFunction(input.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
