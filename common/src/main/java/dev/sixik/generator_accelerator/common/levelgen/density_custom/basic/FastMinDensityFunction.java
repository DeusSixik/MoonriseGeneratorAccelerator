package dev.sixik.generator_accelerator.common.levelgen.density_custom.basic;

import dev.sixik.generator_accelerator.common.levelgen.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastMinDensityFunction(DensityFunction argument1, DensityFunction argument2, double minValue, double maxValue) implements DensityFunction {

    public static final KeyDispatchDataCodec<FastMinDensityFunction> codec =
            DensityCustomFunction.doubleFunctionArgumentCodec(
                    (densityFunction, densityFunction2) ->
                            (FastMinDensityFunction) DensityCustomFunction.createFastVersion(DensityFunctions.TwoArgumentSimpleFunction.Type.MIN, densityFunction, densityFunction2), FastMinDensityFunction::argument1, FastMinDensityFunction::argument2);


    @Override
    public double compute(FunctionContext functionContext) {
        return Math.min(this.argument1.compute(functionContext), this.argument2.compute(functionContext));
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
//        this.argument1.fillArray(ds, contextProvider);
//        double[] es = DensityThreadLocalData.acquire(ds.length);
//
//        try {
//            this.argument2.fillArray(es, contextProvider);
//            for (int i = 0; i < ds.length; ++i) {
//                double value = ds[i];
//                ds[i] = Math.min(value, es[i]);
//            }
//        } finally {
//            DensityThreadLocalData.release();
//        }

        contextProvider.fillAllDirectly(ds, this); // Внутри крутится обычный цикл for с вызовом compute()

    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastMinDensityFunction(argument1.mapAll(visitor), argument2.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return codec;
    }
}
