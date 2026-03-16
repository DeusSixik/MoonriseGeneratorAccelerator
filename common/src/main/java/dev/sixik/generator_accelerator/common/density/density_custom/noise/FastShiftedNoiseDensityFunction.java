package dev.sixik.generator_accelerator.common.density.density_custom.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sixik.generator_accelerator.common.density.density_custom.DensityThreadLocalData;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public record FastShiftedNoiseDensityFunction(
        DensityFunction shiftX,
        DensityFunction shiftY,
        DensityFunction shiftZ,
        double xzScale,
        double yScale,
        NoiseHolder noise
) implements DensityFunction {

    private static final MapCodec<FastShiftedNoiseDensityFunction> DATA_CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group((DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_x"))
                            .forGetter(FastShiftedNoiseDensityFunction::shiftX),
                            (DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_y"))
                                    .forGetter(FastShiftedNoiseDensityFunction::shiftY),
                            (DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_z"))
                                    .forGetter(FastShiftedNoiseDensityFunction::shiftZ),
                            Codec.DOUBLE.fieldOf("xz_scale")
                                    .forGetter(FastShiftedNoiseDensityFunction::xzScale),
                            Codec.DOUBLE.fieldOf("y_scale")
                                    .forGetter(FastShiftedNoiseDensityFunction::yScale),
                            NoiseHolder.CODEC.fieldOf("noise")
                                    .forGetter(FastShiftedNoiseDensityFunction::noise))
                            .apply(instance, FastShiftedNoiseDensityFunction::new));

    public static final KeyDispatchDataCodec<FastShiftedNoiseDensityFunction> CODEC = DensityFunctions.makeCodec(DATA_CODEC);


    @Override
    public double compute(FunctionContext context) {
        double d = (double)context.blockX() * this.xzScale + this.shiftX.compute(context);
        double e = (double)context.blockY() * this.yScale + this.shiftY.compute(context);
        double f = (double)context.blockZ() * this.xzScale + this.shiftZ.compute(context);
        return this.noise.getValue(d, e, f);
    }

    @Override
    public void fillArray(double[] ds, ContextProvider provider) {
        int length = ds.length;

        double[] arrX = DensityThreadLocalData.acquire(length);
        double[] arrY = DensityThreadLocalData.acquire(length);
        double[] arrZ = DensityThreadLocalData.acquire(length);

        try {
            this.shiftX.fillArray(arrX, provider);
            this.shiftY.fillArray(arrY, provider);
            this.shiftZ.fillArray(arrZ, provider);

            for (int i = 0; i < length; i++) {
                FunctionContext ctx = provider.forIndex(i);

                double d = (double)ctx.blockX() * this.xzScale + arrX[i];
                double e = (double)ctx.blockY() * this.yScale + arrY[i];
                double f = (double)ctx.blockZ() * this.xzScale + arrZ[i];

                ds[i] = this.noise.getValue(d, e, f);
            }
        } finally {
            DensityThreadLocalData.release();
            DensityThreadLocalData.release();
            DensityThreadLocalData.release();
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new FastShiftedNoiseDensityFunction(
                this.shiftX.mapAll(visitor),
                this.shiftY.mapAll(visitor),
                this.shiftZ.mapAll(visitor),
                this.xzScale, this.yScale,
                visitor.visitNoise(this.noise)
        );
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    @Override
    public double minValue() {
        return -this.maxValue();
    }

    @Override
    public double maxValue() {
        return this.noise.maxValue();
    }
}
