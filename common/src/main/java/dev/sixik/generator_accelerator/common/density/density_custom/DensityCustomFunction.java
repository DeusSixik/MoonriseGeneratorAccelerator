package dev.sixik.generator_accelerator.common.density.density_custom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.generator_accelerator.common.density.density_custom.basic.FastAddDensityFunction;
import dev.sixik.generator_accelerator.common.density.density_custom.basic.FastMaxDensityFunction;
import dev.sixik.generator_accelerator.common.density.density_custom.basic.FastMinDensityFunction;
import dev.sixik.generator_accelerator.common.density.density_custom.basic.FastMulDensityFunction;
import dev.sixik.generator_accelerator.common.density.density_custom.pure.*;
import net.minecraft.core.Registry;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.function.BiFunction;
import java.util.function.Function;

public class DensityCustomFunction {

    public static final Codec<Double> NOISE_VALUE_CODEC = Codec.doubleRange(-1000000.0, 1000000.0);

    public static <O> KeyDispatchDataCodec<O> doubleFunctionArgumentCodec(BiFunction<DensityFunction, DensityFunction, O> biFunction, Function<O, DensityFunction> function, Function<O, DensityFunction> function2) {
        return KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec) DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument1")).forGetter(function), ((MapCodec) DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument2")).forGetter(function2)).apply(instance, biFunction)));
    }

    public static <O> KeyDispatchDataCodec<O> singleFunctionArgumentCodec(Function<DensityFunction, O> function, Function<O, DensityFunction> function2) {
        return singleArgumentCodec(DensityFunction.HOLDER_HELPER_CODEC, function, function2);
    }

    public static <A, O> KeyDispatchDataCodec<O> singleArgumentCodec(Codec<A> codec, Function<A, O> function, Function<O, A> function2) {
        return KeyDispatchDataCodec.of(((MapCodec)codec.fieldOf("argument")).xmap(function, function2));
    }

    public static MapCodec<? extends DensityFunction> register(Registry<MapCodec<? extends DensityFunction>> registry, String string, KeyDispatchDataCodec<? extends DensityFunction> keyDispatchDataCodec) {
        return Registry.register(registry, string, keyDispatchDataCodec.codec());
    }

    public static DensityFunction createFastVersion(
            DensityFunctions.TwoArgumentSimpleFunction.Type type, DensityFunction argument1, DensityFunction argument2
    ) {
        switch (type) {
            case ADD -> {
                return new FastAddDensityFunction(argument1, argument2,
                        argument1.minValue() + argument2.minValue(),
                        argument1.maxValue() + argument2.maxValue()
                );
            }
            case MUL -> {
                return new FastMulDensityFunction(argument1, argument2,
                        argument1.minValue() * argument2.minValue(),
                        argument1.maxValue() * argument2.maxValue()
                );
            }
            case MAX -> {
                return new FastMaxDensityFunction(argument1, argument2,
                        Math.max(argument1.minValue(), argument1.minValue()),
                        Math.max(argument1.maxValue(), argument2.maxValue())
                );
            }
            case MIN -> {
                return new FastMinDensityFunction(argument1, argument2,
                        Math.min(argument1.minValue(), argument1.minValue()),
                        Math.min(argument1.maxValue(), argument2.maxValue())
                );}
        }

        throw new UnsupportedOperationException();
    }

    public static DensityFunction createFastVersion(DensityFunctions.Mapped.Type type, DensityFunction densityFunction) {

        final double d = densityFunction.minValue();
        final double m = densityFunction.maxValue();
        switch (type) {
            case ABS -> {
                return new FastAbsDensityFunction(densityFunction,
                        Math.max(0.0, d),
                        Math.max(DensityCustomFunction.pureValue(type, d), DensityCustomFunction.pureValue(type, m))
                );
            }
            case SQUARE -> {
                return new FastSquareDensityFunction(densityFunction,
                        Math.max(0.0, d),
                        Math.max(DensityCustomFunction.pureValue(type, d), DensityCustomFunction.pureValue(type, m))
                );
            }
            case CUBE -> {
                return new FastCubeDensityFunction(densityFunction,
                        DensityCustomFunction.pureValue(type, d),
                        DensityCustomFunction.pureValue(type, m)
                );
            }
            case HALF_NEGATIVE -> {
                return new FastHalfNegativeDensityFunction(densityFunction,
                        DensityCustomFunction.pureValue(type, d),
                        DensityCustomFunction.pureValue(type, m)
                );
            }
            case QUARTER_NEGATIVE -> {
                return new FastQuarterNegativeDensityFunction(densityFunction,
                        DensityCustomFunction.pureValue(type, d),
                        DensityCustomFunction.pureValue(type, m)
                );
            }
            case SQUEEZE -> {
                return new FastSqueezeDensityFunction(densityFunction,
                        DensityCustomFunction.pureValue(type, d),
                        DensityCustomFunction.pureValue(type, m)
                );
            }
        }

        throw new UnsupportedOperationException();
    }


    public static double pureValue(DensityFunctions.Mapped.Type type, double d) {
        return switch (type.ordinal()) {
            case 0 -> Math.abs(d);
            case 1 -> d * d;
            case 2 -> d * d * d;
            case 3 -> {
                if (d > 0.0) {
                    yield d;
                }
                yield d * 0.5;
            }
            case 4 -> {
                if (d > 0.0) {
                    yield d;
                }
                yield d * 0.25;
            }
            case 5 -> {
                double e = Mth.clamp(d, -1.0, 1.0);
                yield e / 2.0 - e * e * e / 24.0;
            }
            default -> throw new MatchException(null, null);
        };
    }
}
