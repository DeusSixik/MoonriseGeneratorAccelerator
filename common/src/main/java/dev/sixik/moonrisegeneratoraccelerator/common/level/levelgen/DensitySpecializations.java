package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.jetbrains.annotations.NotNull;

@Deprecated
public class DensitySpecializations {

    public record FastAdd(DensityFunction a, DensityFunction b) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return a.compute(ctx) + b.compute(ctx);
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            final int l = ds.length;
            a.fillArray(ds, ptr);
            double[] buf = new double[l];
            b.fillArray(buf, ptr);
            for (int i = 0; i < l; i++) ds[i] += buf[i];
        }

        @Override
        public @NotNull DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastAdd(a.mapAll(visitor), b.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return a.minValue() + b.minValue();
        }

        @Override
        public double maxValue() {
            return a.maxValue() + b.maxValue();
        }

        @Override
        public @NotNull KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.ADD.codec;
        }
    }

    public record FastMul(DensityFunction a, DensityFunction b) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            double v1 = a.compute(ctx);
            return v1 == 0.0 ? 0.0 : v1 * b.compute(ctx);
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            final int l = ds.length;
            a.fillArray(ds, ptr);
            final double[] buf = new double[l];
            b.fillArray(buf, ptr);
            for (int i = 0; i < l; i++) {
                ds[i] = (ds[i] == 0.0) ? 0.0 : ds[i] * buf[i];
            }
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastMul(a.mapAll(visitor), b.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public @NotNull KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.MUL.codec;
        }
    }

    public record FastMin(DensityFunction a, DensityFunction b) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            final double v1 = a.compute(ctx);
            if (v1 < b.minValue()) return v1;
            return Math.min(v1, b.compute(ctx));
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            final int l = ds.length;
            a.fillArray(ds, ptr);
            final double[] buf = new double[l];
            b.fillArray(buf, ptr);
            for (int i = 0; i < l; i++) ds[i] = Math.min(ds[i], buf[i]);
        }

        @Override
        public @NotNull DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastMin(a.mapAll(visitor), b.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Math.min(a.minValue(), b.minValue());
        }

        @Override
        public double maxValue() {
            return Math.min(a.maxValue(), b.maxValue());
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.MIN.codec;
        }
    }

    public record FastMax(DensityFunction a, DensityFunction b) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            final double v1 = a.compute(ctx);
            if (v1 > b.maxValue()) return v1;
            return Math.max(v1, b.compute(ctx));
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            final int l = ds.length;
            a.fillArray(ds, ptr);
            final double[] buf = new double[l];
            b.fillArray(buf, ptr);
            for (int i = 0; i < l; i++) ds[i] = Math.max(ds[i], buf[i]);
        }

        @Override
        public @NotNull DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastMax(a.mapAll(visitor), b.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Math.max(a.minValue(), b.minValue());
        }

        @Override
        public double maxValue() {
            return Math.max(a.maxValue(), b.maxValue());
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.MAX.codec;
        }
    }

    public record FastLinearAdd(DensityFunction input, double offset) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return input.compute(ctx) + offset;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) ds[i] += offset;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastLinearAdd(input.mapAll(visitor), offset));
        }

        @Override
        public double minValue() {
            return input.minValue() + offset;
        }

        @Override
        public double maxValue() {
            return input.maxValue() + offset;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.ADD.codec;
        }
    }

    public record FastLinearMul(DensityFunction input, double factor) implements DensityFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return input.compute(ctx) * factor;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) ds[i] *= factor;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastLinearMul(input.mapAll(visitor), factor));
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.TwoArgumentSimpleFunction.Type.MUL.codec;
        }
    }
}
