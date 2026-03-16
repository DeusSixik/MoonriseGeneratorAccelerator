package dev.sixik.generator_accelerator.common.density.density_custom.pure;

import dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastAbsDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastAbsDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastAbsDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.ABS, densityFunction), FastAbsDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        return Math.abs(input.compute(functionContext));
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            ds[i] = Math.abs(ds[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastAbsDensityFunction(input.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
