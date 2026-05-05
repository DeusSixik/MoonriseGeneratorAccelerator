package dev.sixik.generator_accelerator.common.density.compiler.compiler.compat;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Generator Accelerator replaces several registry codecs with
 * {@code dev.sixik.generator_accelerator…Fast*} density function classes that are
 * drop-in with vanilla but are not {@code instanceof} the
 * {@link net.minecraft.world.level.levelgen.DensityFunctions} record types. Without
 * this pass they would always compile to {@link IRNode.Invoke}.</p>
 *
 * <p>We unwrap known GA records using reflection so {@link IRBuilder} can emit the same
 * IR as for vanilla, without a compile or runtime mod dependency on GA. If GA renames
 * or adds types, unrecognised classes fall back to the normal {@code Invoke} path.</p>
 */
public final class GeneratorAcceleratorInline {

    /** Use with {@code DensityFunction#getClass()}{@code .getName()}{@code .startsWith(PACKAGE_PREFIX)}. */
    public static final String PACKAGE_PREFIX = "dev.sixik.generator_accelerator";

    private static final String BASE = "dev.sixik.generator_accelerator.common.density.density_custom";

    public static final String C_FAST_ADD = BASE + ".basic.FastAddDensityFunction";
    public static final String C_FAST_MUL = BASE + ".basic.FastMulDensityFunction";
    public static final String C_FAST_MAX = BASE + ".basic.FastMaxDensityFunction";
    public static final String C_FAST_MIN = BASE + ".basic.FastMinDensityFunction";
    public static final String C_FAST_SHIFTED_NOISE = BASE + ".noise.FastShiftedNoiseDensityFunction";
    public static final String C_FAST_RANGE_CHOICE = BASE + ".misc.FastRangeChoice";
    public static final String C_FAST_ABS = BASE + ".pure.FastAbsDensityFunction";
    public static final String C_FAST_SQUARE = BASE + ".pure.FastSquareDensityFunction";
    public static final String C_FAST_CUBE = BASE + ".pure.FastCubeDensityFunction";
    public static final String C_FAST_HALF_NEGATIVE = BASE + ".pure.FastHalfNegativeDensityFunction";
    public static final String C_FAST_QUARTER_NEGATIVE = BASE + ".pure.FastQuarterNegativeDensityFunction";
    public static final String C_FAST_SQUEEZE = BASE + ".pure.FastSqueezeDensityFunction";
    public static final String C_FAST_CLAMP = BASE + ".pure.FastClampDensityFunction";

    private static final Map<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private record MethodKey(Class<?> clazz, String name) {}

    private GeneratorAcceleratorInline() {}

    /**
     * If {@code df} is a known GA type, return the same IR the vanilla
     * {@code DensityFunctions} counterpart would get; otherwise {@code null}.
     */
    public static IRNode tryUnwrap(DensityFunction df, IRBuilder b) {
        String n = df.getClass().getName();
        if (!n.startsWith(PACKAGE_PREFIX)) {
            return null;
        }
        try {
            return tryUnwrapInner(df, b);
        } catch (Exception e) {
            return null;
        }
    }

    private static IRNode tryUnwrapInner(DensityFunction df, IRBuilder b) throws Exception {
        ConstantPool pool = b.pool();
        String n = df.getClass().getName();
        if (C_FAST_ADD.equals(n)) {
            return b.intern(new IRNode.Bin(
                    IRNode.BinOp.ADD, walkInvoke(df, "argument1", b), walkInvoke(df, "argument2", b)));
        }
        if (C_FAST_MUL.equals(n)) {
            return b.intern(new IRNode.Bin(
                    IRNode.BinOp.MUL, walkInvoke(df, "argument1", b), walkInvoke(df, "argument2", b)));
        }
        if (C_FAST_MAX.equals(n)) {
            return b.intern(new IRNode.Bin(
                    IRNode.BinOp.MAX, walkInvoke(df, "argument1", b), walkInvoke(df, "argument2", b)));
        }
        if (C_FAST_MIN.equals(n)) {
            return b.intern(new IRNode.Bin(
                    IRNode.BinOp.MIN, walkInvoke(df, "argument1", b), walkInvoke(df, "argument2", b)));
        }
        if (C_FAST_RANGE_CHOICE.equals(n)) {
            return b.intern(new IRNode.RangeChoice(
                    walkInvoke(df, "input", b),
                    (Double) call(df, "minInclusive"),
                    (Double) call(df, "maxExclusive"),
                    walkInvoke(df, "whenInRange", b),
                    walkInvoke(df, "whenOutOfRange", b)));
        }
        if (C_FAST_CLAMP.equals(n)) {
            return b.intern(new IRNode.Clamp(
                    walkInvoke(df, "input", b),
                    (Double) call(df, "minValue"),
                    (Double) call(df, "maxValue")));
        }
        if (C_FAST_ABS.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.ABS, walkInvoke(df, "input", b)));
        }
        if (C_FAST_SQUARE.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.SQUARE, walkInvoke(df, "input", b)));
        }
        if (C_FAST_CUBE.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.CUBE, walkInvoke(df, "input", b)));
        }
        if (C_FAST_HALF_NEGATIVE.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.HALF_NEGATIVE, walkInvoke(df, "input", b)));
        }
        if (C_FAST_QUARTER_NEGATIVE.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.QUARTER_NEGATIVE, walkInvoke(df, "input", b)));
        }
        if (C_FAST_SQUEEZE.equals(n)) {
            return b.intern(new IRNode.Unary(
                    IRNode.UnaryOp.SQUEEZE, walkInvoke(df, "input", b)));
        }
        if (C_FAST_SHIFTED_NOISE.equals(n)) {
            DensityFunction.NoiseHolder noiseHolder = (DensityFunction.NoiseHolder) call(df, "noise");
            NormalNoise noise = noiseHolder.noise();
            if (noise == null) {
                return b.intern(new IRNode.Const(0.0));
            }
            int idx = pool.internNoise(noise);
            IRNode sx = walkInvoke(df, "shiftX", b);
            IRNode sy = walkInvoke(df, "shiftY", b);
            IRNode sz = walkInvoke(df, "shiftZ", b);
            return b.intern(new IRNode.ShiftedNoise(
                    idx,
                    (Double) call(df, "xzScale"),
                    (Double) call(df, "yScale"),
                    sx, sy, sz,
                    noiseHolder.maxValue()));
        }
        return null;
    }

    private static IRNode walkInvoke(DensityFunction obj, String method, IRBuilder b) throws Exception {
        return b.walkChild((DensityFunction) call(obj, method));
    }

    private static Object call(DensityFunction obj, String method) throws Exception {
        Class<?> c = obj.getClass();
        Method m = METHOD_CACHE.computeIfAbsent(new MethodKey(c, method), k -> {
            try {
                return c.getMethod(k.name());
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            return m.invoke(obj);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception ex) {
                throw ex;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }
}
