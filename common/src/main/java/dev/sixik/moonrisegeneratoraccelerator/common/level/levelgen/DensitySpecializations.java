package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.utils.wrappers.DensityCompilerFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class DensitySpecializations {

    public record FastAdd(DensityFunction original, DensityFunction a, DensityFunction b)
            implements DensityFunction, DensityCompilerFunction {
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
            return visitor.apply(new FastAdd(original, a.mapAll(visitor), b.mapAll(visitor)));
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

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastMul(DensityFunction original, DensityFunction a, DensityFunction b)
            implements DensityFunction, DensityCompilerFunction {
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
            return visitor.apply(new FastMul(original, a.mapAll(visitor), b.mapAll(visitor)));
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

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastMin(DensityFunction original, DensityFunction a, DensityFunction b)
            implements DensityFunction, DensityCompilerFunction {
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
            return visitor.apply(new FastMin(original, a.mapAll(visitor), b.mapAll(visitor)));
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

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastMax(DensityFunction original, DensityFunction a, DensityFunction b)
            implements DensityFunction, DensityCompilerFunction {
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
            return visitor.apply(new FastMax(original, a.mapAll(visitor), b.mapAll(visitor)));
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

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastAddConstant(DensityFunction original, DensityFunction input, double argument, double minValue, double maxValue)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return input.compute(ctx) + argument;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            final int l = ds.length;
            final double arg = this.argument;
            for (int i = 0; i < l; i++) ds[i] += arg;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            DensityFunction newInput = input.mapAll(visitor);
            return visitor.apply(new FastAddConstant(original, newInput, argument, newInput.minValue() + argument, newInput.maxValue() + argument));
        }

        @Override public double minValue() { return minValue; }
        @Override public double maxValue() { return maxValue; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DensityFunctions.TwoArgumentSimpleFunction.Type.ADD.codec; }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastMulConstant(DensityFunction original, DensityFunction input, double argument, double minValue, double maxValue)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            if (argument == 0.0) return 0.0;
            return input.compute(ctx) * argument;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            if (argument == 0.0) {
                Arrays.fill(ds, 0.0);
                return;
            }
            input.fillArray(ds, ptr);
            final int l = ds.length;
            final double arg = this.argument;
            for (int i = 0; i < l; i++) ds[i] *= arg;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            DensityFunction ni = input.mapAll(visitor);
            double d = ni.minValue(), e = ni.maxValue();
            double f = argument >= 0.0 ? d * argument : e * argument;
            double g = argument >= 0.0 ? e * argument : d * argument;
            return visitor.apply(new FastMulConstant(original, ni, argument, f, g));
        }

        @Override public double minValue() { return minValue; }
        @Override public double maxValue() { return maxValue; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DensityFunctions.TwoArgumentSimpleFunction.Type.MUL.codec; }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastAbs(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return Math.abs(input.compute(ctx));
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                ds[i] = Math.abs(ds[i]);
            }
        }

        @Override
        public double minValue() {
            return Math.max(0.0, input.minValue());
        }

        @Override
        public double maxValue() {
            return Math.max(Math.abs(input.minValue()), Math.abs(input.maxValue()));
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastAbs(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastSquare(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            double d = input.compute(ctx);
            return d * d;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                double d = ds[i];
                ds[i] = d * d;
            }
        }

        @Override
        public double minValue() {
            double min = input.minValue();
            double max = input.maxValue();
            return (min <= 0.0 && max >= 0.0) ? 0.0 : Math.min(min * min, max * max);
        }

        @Override
        public double maxValue() {
            double min = input.minValue();
            double max = input.maxValue();
            return Math.max(min * min, max * max);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastSquare(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastCube(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            double d = input.compute(ctx);
            return d * d * d;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                double d = ds[i];
                ds[i] = d * d * d;
            }
        }

        @Override
        public double minValue() {
            double min = input.minValue();
            return min * min * min;
        }

        @Override
        public double maxValue() {
            double max = input.maxValue();
            return max * max * max;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastCube(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastHalfNegative(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            double d = input.compute(ctx);
            return d > 0.0 ? d : d * 0.5;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                double d = ds[i];
                ds[i] = d > 0.0 ? d : d * 0.5;
            }
        }

        @Override
        public double minValue() {
            double min = input.minValue();
            return min > 0.0 ? min : min * 0.5;
        }

        @Override
        public double maxValue() {
            double max = input.maxValue();
            return max > 0.0 ? max : max * 0.5;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastHalfNegative(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastQuarterNegative(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        @Override
        public double compute(FunctionContext ctx) {
            double d = input.compute(ctx);
            return d > 0.0 ? d : d * 0.25;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                double d = ds[i];
                ds[i] = d > 0.0 ? d : d * 0.25;
            }
        }

        @Override
        public double minValue() {
            double min = input.minValue();
            return min > 0.0 ? min : min * 0.25;
        }

        @Override
        public double maxValue() {
            double max = input.maxValue();
            return max > 0.0 ? max : max * 0.25;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastQuarterNegative(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public record FastSqueeze(DensityFunction original, DensityFunction input)
            implements DensityFunction, DensityCompilerFunction {
        private static final double INV_24 = 1.0 / 24.0;

        @Override
        public double compute(FunctionContext ctx) {
            double d = input.compute(ctx);
            double clamped = d < -1.0 ? -1.0 : (d > 1.0 ? 1.0 : d);
            return clamped * 0.5 - clamped * clamped * clamped * INV_24;
        }

        @Override
        public void fillArray(double[] ds, ContextProvider ptr) {
            input.fillArray(ds, ptr);
            for (int i = 0; i < ds.length; i++) {
                double d = ds[i];
                double clamped = d < -1.0 ? -1.0 : (d > 1.0 ? 1.0 : d);
                ds[i] = clamped * 0.5 - clamped * clamped * clamped * INV_24;
            }
        }

        @Override
        public double minValue() {
            return -0.4583333333333333;
        }

        @Override
        public double maxValue() {
            return 0.4583333333333333;
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new FastSqueeze(original, input.mapAll(visitor)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new NotImplementedException("Not needed on runtime!");
        }

        @Override
        public DensityFunction getRootFunction() {
            return original;
        }
    }

    public static DensityFunction create(DensityFunctions.Mapped.Type type, DensityFunction original, DensityFunction input) {
        return switch (type) {
            case ABS -> new FastAbs(original, input);
            case SQUARE -> new FastSquare(original, input);
            case CUBE -> new FastCube(original, input);
            case HALF_NEGATIVE -> new FastHalfNegative(original, input);
            case QUARTER_NEGATIVE -> new FastQuarterNegative(original, input);
            case SQUEEZE -> new FastSqueeze(original, input);
        };
    }
}
