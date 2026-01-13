package dev.sixik.density_compiller.compiler.wrappers;

import it.unimi.dsi.fastutil.doubles.Double2DoubleFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record MapperWrapper(Double2DoubleFunction mapper) implements DensityFunction {

    @Override public double compute(FunctionContext ctx) { return 0; }
    @Override public void fillArray(double[] ds, ContextProvider cp) {}

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return null;
    }

    @Override public double minValue() { return 0; }
    @Override public double maxValue() { return 0; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
}
