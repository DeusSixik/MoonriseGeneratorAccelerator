package dev.sixik.generator_accelerator.common.density.density_custom.misc;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.generator_accelerator.common.density.density_custom.DensityThreadLocalData;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import static dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction.NOISE_VALUE_CODEC;


public record FastRangeChoice(DensityFunction input, double minInclusive, double maxExclusive, DensityFunction whenInRange, DensityFunction whenOutOfRange) implements DensityFunction {

    public static final MapCodec<FastRangeChoice> DATA_CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group((DensityFunction.HOLDER_HELPER_CODEC
                                            .fieldOf("input")).forGetter(FastRangeChoice::input),
                                    (NOISE_VALUE_CODEC.fieldOf("min_inclusive"))
                                            .forGetter(FastRangeChoice::minInclusive),
                                    (NOISE_VALUE_CODEC.fieldOf("max_exclusive"))
                                            .forGetter(FastRangeChoice::maxExclusive),
                                    (DensityFunction.HOLDER_HELPER_CODEC.fieldOf("when_in_range"))
                                            .forGetter(FastRangeChoice::whenInRange),
                                    (DensityFunction.HOLDER_HELPER_CODEC.fieldOf("when_out_of_range"))
                                            .forGetter(FastRangeChoice::whenOutOfRange))
                            .apply(instance, FastRangeChoice::new));

    public static final KeyDispatchDataCodec<FastRangeChoice> CODEC =
            DensityFunctions.makeCodec(DATA_CODEC);

    @Override
    public double compute(FunctionContext context) {
        double d = this.input.compute(context);
        if (d >= this.minInclusive && d < this.maxExclusive) {
            return this.whenInRange.compute(context);
        }
        return this.whenOutOfRange.compute(context);
    }

    @Override
    public void fillArray(double[] ds, ContextProvider provider) {
        this.input.fillArray(ds, provider);

        boolean anyTrue = false;
        boolean anyFalse = false;

        for (int i = 0; i < ds.length; i++) {
            double v = ds[i];
            boolean inRange = v >= this.minInclusive && v < this.maxExclusive;
            if (inRange) {
                anyTrue = true;
            } else {
                anyFalse = true;
            }
            if (anyTrue && anyFalse) break;
        }

        if (anyTrue && !anyFalse) {
            this.whenInRange.fillArray(ds, provider);
        } else if (anyFalse && !anyTrue) {
            this.whenOutOfRange.fillArray(ds, provider);
        } else {
            for (int i = 0; i < ds.length; i++) {
                double v = ds[i];
                if (v >= this.minInclusive && v < this.maxExclusive) {
                    ds[i] = this.whenInRange.compute(provider.forIndex(i));
                } else {
                    ds[i] = this.whenOutOfRange.compute(provider.forIndex(i));
                }
            }
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new FastRangeChoice(this.input.mapAll(visitor), this.minInclusive, this.maxExclusive, this.whenInRange.mapAll(visitor), this.whenOutOfRange.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return Math.min(this.whenInRange.minValue(), this.whenOutOfRange.minValue());
    }

    @Override
    public double maxValue() {
        return Math.max(this.whenInRange.maxValue(), this.whenOutOfRange.maxValue());
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
