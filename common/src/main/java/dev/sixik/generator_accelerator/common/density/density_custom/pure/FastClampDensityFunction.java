package dev.sixik.generator_accelerator.common.density.density_custom.pure;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.generator_accelerator.common.density.density_custom.DensityCustomFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastClampDensityFunction(DensityFunction input, double minValue, double maxValue) implements DensityFunction {

    private static final MapCodec<FastClampDensityFunction> DATA_CODEC =
            RecordCodecBuilder.mapCodec((instance) ->
                    instance.group(DensityFunction.DIRECT_CODEC.fieldOf("input")
                            .forGetter(FastClampDensityFunction::input),
                            DensityCustomFunction.NOISE_VALUE_CODEC.fieldOf("min")
                                    .forGetter(FastClampDensityFunction::minValue),
                            DensityCustomFunction.NOISE_VALUE_CODEC.fieldOf("max")
                                    .forGetter(FastClampDensityFunction::maxValue))
                            .apply(instance, FastClampDensityFunction::new));

    public static final KeyDispatchDataCodec<FastClampDensityFunction> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

    @Override
    public double compute(FunctionContext functionContext) {
        return Mth.clamp(input.compute(functionContext), minValue, maxValue);
    }

    @Override
    public void fillArray(double[] ds, ContextProvider contextProvider) {
        input.fillArray(ds, contextProvider);
        for (int i = 0; i < ds.length; ++i) {
            ds[i] = Mth.clamp(ds[i], minValue, maxValue);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastClampDensityFunction(input.mapAll(visitor), minValue, maxValue);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
