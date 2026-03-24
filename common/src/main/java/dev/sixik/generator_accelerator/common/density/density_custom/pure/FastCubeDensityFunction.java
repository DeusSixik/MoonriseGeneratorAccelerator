package dev.sixik.generator_accelerator.common.density.density_custom.pure;

import dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastCubeDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastCubeDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastCubeDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.CUBE, densityFunction), FastCubeDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        final double value = input.compute(functionContext);
        return value * value * value;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            double value = ds[i];
            ds[i] = (value * value);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new FastCubeDensityFunction(input.mapAll(visitor), minValue, maxValue));
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
