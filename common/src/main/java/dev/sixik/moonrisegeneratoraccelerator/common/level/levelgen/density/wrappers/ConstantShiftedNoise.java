package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.density.wrappers;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.apache.commons.lang3.NotImplementedException;

public record ConstantShiftedNoise(
        double shiftX, double shiftY, double shiftZ,
        double xzScale, double yScale,
        DensityFunction.NoiseHolder noise
) implements DensityFunction {

    @Override
    public double compute(DensityFunction.FunctionContext arg) {
        final double xz = this.xzScale;
        return this.noise.getValue(
                arg.blockX() * xz + this.shiftX,
                arg.blockY() * this.yScale + this.shiftY,
                arg.blockZ() * xz + this.shiftZ
        );
    }

    @Override
    public void fillArray(double[] ds, DensityFunction.ContextProvider arg) {
        arg.fillAllDirectly(ds, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new ConstantShiftedNoise(shiftX, shiftY, shiftZ, xzScale, yScale, noise);
    }

    @Override
    public double minValue() {
        return -maxValue();
    }

    @Override
    public double maxValue() {
        return this.noise.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new NotImplementedException("Codec not need for runtime generation");
    }
}
