package dev.sixik.generator_accelerator.common.density.density_custom.basic;

import dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction;
import dev.sixik.generator_accelerator.common.density.density_custom.DensityThreadLocalData;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastMulDensityFunction(DensityFunction argument1, DensityFunction argument2, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastMulDensityFunction> codec =
            DensityCustomFunction.doubleFunctionArgumentCodec(
                    (densityFunction, densityFunction2) ->
                            (FastMulDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.TwoArgumentSimpleFunction.Type.MUL, densityFunction, densityFunction2), FastMulDensityFunction::argument1, FastMulDensityFunction::argument2);


    @Override
    public double compute(FunctionContext functionContext) {
        return this.argument1.compute(functionContext) * this.argument2.compute(functionContext);
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        this.argument1.fillArray(ds, contextProvider);
        double[] es = DensityThreadLocalData.acquire(ds.length);
        try {
            this.argument2.fillArray(es, contextProvider);
            for (int i = 0; i < ds.length; ++i) {
                ds[i] *= es[i];
            }
        } finally {
            DensityThreadLocalData.release();
        }

//        contextProvider.fillAllDirectly(ds, this); // Внутри крутится обычный цикл for с вызовом compute()

    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastMulDensityFunction(argument1.mapAll(visitor), argument2.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
