package dev.sixik.density_compiller.compiler.wrappers;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record PublicNoiseWrapper(DensityFunction.NoiseHolder holder) implements DensityFunction {

    @Override
    public double compute(FunctionContext context) {
        /*
            This method should NOT be called in the optimized ShiftedNoise code.
            We manually take out the holder there and call holder.getValue(...).
            But for safety, we'll return 0.
         */
        return 0.0;
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        // No-op
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {

        /*
            The standard visitor implementation, just in case
         */
        return visitor.apply(new PublicNoiseWrapper(visitor.visitNoise(this.holder)));
    }

    @Override
    public double minValue() {

        /*
            Delegating boundaries to noise (important for range checks)
         */
        return -this.maxValue();
    }

    @Override
    public double maxValue() {
        return this.holder.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        /*
            This object exists only in runtime during generation.
            Serialize (save to disk) it is not needed.
            Returning null
         */
        return null;
    }
}
