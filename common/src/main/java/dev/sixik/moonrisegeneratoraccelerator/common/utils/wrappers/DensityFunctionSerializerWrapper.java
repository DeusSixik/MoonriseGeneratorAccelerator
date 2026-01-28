package dev.sixik.moonrisegeneratoraccelerator.common.utils.wrappers;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class DensityFunctionSerializerWrapper implements DensityFunction {

    protected final DensityFunction densityFunction;

    public DensityFunctionSerializerWrapper(DensityFunction densityFunction) {
        this.densityFunction = densityFunction;
    }

    @Override
    public double compute(FunctionContext functionContext) {
        return densityFunction.compute(functionContext);
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        densityFunction.fillArray(ds, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return densityFunction.mapAll(visitor);
    }

    @Override
    public double minValue() {
        return densityFunction.minValue();
    }

    @Override
    public double maxValue() {
        return densityFunction.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException("Can't serialize dynamic class!");
    }

    public DensityFunction wrapped() {
        return densityFunction;
    }

    public static DensityFunction getOriginal(DensityFunction func) {
        if (func instanceof DensityFunctionSerializerWrapper wrapper) {

            final var wrap = wrapper.wrapped();

            if (wrap instanceof DensityCompilerFunction compilerFunction)
                return compilerFunction.getRootFunction();

            return wrap;

        }

        if (func instanceof DensityCompilerFunction compilerFunction)
            return compilerFunction.getRootFunction();

        return func;
    }
}
