package dev.sixik.generator_accelerator.common.levelgen.density_custom.pure;

import dev.sixik.generator_accelerator.common.levelgen.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastSqueezeDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastSqueezeDensityFunction> codec =
            DensityCustomFunction.singleFunctionArgumentCodec(densityFunction ->
                    (FastSqueezeDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.Mapped.Type.SQUEEZE, densityFunction), FastSqueezeDensityFunction::input);


    @Override
    public double compute(FunctionContext functionContext) {
        double e = Mth.clamp(input.compute(functionContext), -1.0, 1.0);
        return e / 2.0 - e * e * e / 24.0;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            double e = Mth.clamp(ds[i], -1.0, 1.0);
            ds[i] = e / 2.0 - e * e * e / 24.0;
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastSqueezeDensityFunction(input.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
