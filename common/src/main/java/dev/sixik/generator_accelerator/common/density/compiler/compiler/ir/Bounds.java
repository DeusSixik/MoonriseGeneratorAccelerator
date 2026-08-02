package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.IdentityHashMap;

/**
 * Interval-arithmetic propagation that mirrors vanilla
 * {@link net.minecraft.world.level.levelgen.DensityFunctions} {@code minValue}/{@code
 * maxValue} computation. The compiled function exposes the result so that anything that
 * uses these bounds (range-choice short-circuiting, surface-rule thresholds) sees
 * identical numbers.
 *
 * <p>Interval results are {@link IdentityHashMap memoized} per top-level
 * {@link #interval} call: the IR is a DAG, so a naive recursion revisits shared nodes on
 * every path and blows up to exponential work without caching.
 */
public final class Bounds {
    private Bounds() {}

    public static double min(IRNode node, ConstantPool pool) {
        return interval(node, pool)[0];
    }

    public static double max(IRNode node, ConstantPool pool) {
        return interval(node, pool)[1];
    }

    public static double[] interval(IRNode node, ConstantPool pool) {
        IdentityHashMap<IRNode, double[]> memo = new IdentityHashMap<>();
        return intervalImpl(node, pool, memo);
    }

    private static double[] unboundedCoordinateInterval() {
        return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
    }

    private static double[] yCoordinateInterval() {
        return new double[]{DimensionType.MIN_Y * 2.0, DimensionType.MAX_Y * 2.0};
    }

    private static double[] intervalImpl(IRNode node, ConstantPool pool, IdentityHashMap<IRNode, double[]> memo) {
        double[] hit = memo.get(node);
        if (hit != null) {
            return hit;
        }
        double[] out = switch (node) {
            case IRNode.Const c -> new double[]{c.value(), c.value()};
            // X/Z coordinates are horizontal block coordinates, not vertical build-height
            // coordinates. Treat them as unbounded so interval-driven peepholes never prove
            // far positive/negative horizontal ranges impossible just because Y is finite.
            case IRNode.BlockX bx -> unboundedCoordinateInterval();
            case IRNode.BlockY by -> yCoordinateInterval();
            case IRNode.BlockZ bz -> unboundedCoordinateInterval();

            case IRNode.Bin bin -> binInterval(bin, pool, memo);
            case IRNode.Unary u -> unaryInterval(u, pool, memo);

            case IRNode.Clamp cl -> {
                double[] in = intervalImpl(cl.input(), pool, memo);
                yield new double[]{
                        Math.max(cl.min(), Math.min(cl.max(), in[0])),
                        Math.max(cl.min(), Math.min(cl.max(), in[1]))};
            }

            case IRNode.RangeChoice rc -> {
                double[] hi = intervalImpl(rc.whenInRange(), pool, memo);
                double[] lo = intervalImpl(rc.whenOutOfRange(), pool, memo);
                yield new double[]{Math.min(hi[0], lo[0]), Math.max(hi[1], lo[1])};
            }

            case IRNode.YClampedGradient g ->
                    new double[]{Math.min(g.fromValue(), g.toValue()), Math.max(g.fromValue(), g.toValue())};

            case IRNode.Noise n -> new double[]{-n.maxValue(), n.maxValue()};
            case IRNode.ShiftedNoise sn -> new double[]{-sn.maxValue(), sn.maxValue()};
            case IRNode.ShiftA sa -> new double[]{-sa.maxValue() * 4.0, sa.maxValue() * 4.0};
            case IRNode.ShiftB sb -> new double[]{-sb.maxValue() * 4.0, sb.maxValue() * 4.0};
            case IRNode.Shift s -> new double[]{-s.maxValue() * 4.0, s.maxValue() * 4.0};

            case IRNode.WeirdScaled w -> new double[]{0.0, w.maxValue()};
            // Inlined unrolled noise has the same bounds as the un-inlined NormalNoise
            // it replaces — symmetric around 0 with magnitude `maxValue` (which is the
            // propagated NormalNoise.maxValue() captured at NoiseExpander time, so even
            // routers that sample noise at multiple scales bound correctly).
            case IRNode.InlinedNoise n -> new double[]{-n.maxValue(), n.maxValue()};
            case IRNode.InlinedBlendedNoise b -> new double[]{-b.maxValue(), b.maxValue()};
            // WeirdRarity codomain is fixed by the rarity-table values in
            // Runtime.weirdRarity: {0.5, 0.75, 1.0, 1.5, 2.0, 3.0}. The widest
            // ordinal-2 (RarityValueMapper.TYPE2/2D) hits 3.0; ordinal-1 (TYPE1/3D)
            // tops out at 2.0. Either way the lower bound is 0.5. Bounds.interval is
            // intentionally loose (matches DensityFunctions.WeirdScaledSampler's own
            // [0, maxValue] policy) but tighter than [-Inf, Inf] which would defeat
            // RangeChoice short-circuiting.
            case IRNode.WeirdRarity wr -> new double[]{0.5, wr.rarityValueMapperOrdinal() == 0 ? 2.0 : 3.0};
            case IRNode.EndIslands e -> new double[]{-0.84375, 0.5625};

            case IRNode.Spline.Constant sc -> new double[]{sc.value(), sc.value()};
            case IRNode.Spline.Multipoint mp -> new double[]{mp.minValue(), mp.maxValue()};

            case IRNode.Marker m -> {
                var df = pool.extern(m.externIndex());
                yield new double[]{df.minValue(), df.maxValue()};
            }
            case IRNode.Invoke iv -> {
                var df = pool.extern(iv.externIndex());
                yield new double[]{df.minValue(), df.maxValue()};
            }
            case IRNode.Beardifier b -> {
                var df = pool.extern(b.externIndex());
                yield new double[]{df.minValue(), df.maxValue()};
            }
            case IRNode.BlendDensity bd ->
                    new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        };
        memo.put(node, out);
        return out;
    }

    private static double[] binInterval(IRNode.Bin bin, ConstantPool pool, IdentityHashMap<IRNode, double[]> memo) {
        double[] a = intervalImpl(bin.left(), pool, memo);
        double[] b = intervalImpl(bin.right(), pool, memo);
        double a0 = a[0], a1 = a[1], b0 = b[0], b1 = b[1];
        return switch (bin.op()) {
            case ADD -> new double[]{a0 + b0, a1 + b1};
            case SUB -> new double[]{a0 - b1, a1 - b0};
            case MUL -> {
                double c1 = a0 * b0, c2 = a0 * b1, c3 = a1 * b0, c4 = a1 * b1;
                yield new double[]{Math.min(Math.min(c1, c2), Math.min(c3, c4)),
                        Math.max(Math.max(c1, c2), Math.max(c3, c4))};
            }
            case DIV -> {
                if (b0 <= 0.0 && b1 >= 0.0) {
                    yield new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
                }
                double c1 = a0 / b0, c2 = a0 / b1, c3 = a1 / b0, c4 = a1 / b1;
                yield new double[]{Math.min(Math.min(c1, c2), Math.min(c3, c4)),
                        Math.max(Math.max(c1, c2), Math.max(c3, c4))};
            }
            case MIN -> new double[]{Math.min(a0, b0), Math.min(a1, b1)};
            case MAX -> new double[]{Math.max(a0, b0), Math.max(a1, b1)};
        };
    }

    private static double[] unaryInterval(IRNode.Unary u, ConstantPool pool, IdentityHashMap<IRNode, double[]> memo) {
        double[] a = intervalImpl(u.input(), pool, memo);
        return switch (u.op()) {
            // ABS / SQUARE intentionally use vanilla DensityFunctions.Mapped.create's
            // loose bounds — `[max(0, input.min), max(transform(input.min), transform(
            // input.max))]` — instead of the tighter intervals the input range admits.
            // Why: every other DensityFunction in the tree (TwoArgumentSimpleFunction
            // short-circuits, RangeChoice, Mapped's own propagation) was tuned against
            // those exact loose numbers. Tighter bounds make us short-circuit MORE often
            // than vanilla, which (a) emits spurious "non-overlapping inputs" WARN spam
            // when vanilla rebuilds nested TASF.create with our compiled args, and (b)
            // changes which side of MIN/MAX we evaluate first — fine in pure arithmetic
            // but observable when the un-evaluated side is a CacheOnce/NoiseInterpolator
            // whose first sample seeds chunk-wide caches.
            case ABS -> {
                double lo = Math.max(0.0, a[0]);
                double hi = Math.max(Math.abs(a[0]), Math.abs(a[1]));
                yield new double[]{lo, hi};
            }
            case NEG -> new double[]{-a[1], -a[0]};
            case SQUARE -> {
                double s0 = a[0] * a[0], s1 = a[1] * a[1];
                double lo = Math.max(0.0, a[0]);
                yield new double[]{lo, Math.max(s0, s1)};
            }
            case CUBE -> {
                double c0 = a[0] * a[0] * a[0], c1 = a[1] * a[1] * a[1];
                yield new double[]{Math.min(c0, c1), Math.max(c0, c1)};
            }
            case HALF_NEGATIVE -> {
                double t0 = a[0] > 0.0 ? a[0] : a[0] * 0.5;
                double t1 = a[1] > 0.0 ? a[1] : a[1] * 0.5;
                yield new double[]{Math.min(t0, t1), Math.max(t0, t1)};
            }
            case QUARTER_NEGATIVE -> {
                double t0 = a[0] > 0.0 ? a[0] : a[0] * 0.25;
                double t1 = a[1] > 0.0 ? a[1] : a[1] * 0.25;
                yield new double[]{Math.min(t0, t1), Math.max(t0, t1)};
            }
            case SQUEEZE -> {
                double t0 = squeeze(a[0]);
                double t1 = squeeze(a[1]);
                yield new double[]{Math.min(t0, t1), Math.max(t0, t1)};
            }
        };
    }

    public static double squeeze(double x) {
        double clamped = Math.max(-1.0, Math.min(1.0, x));
        return clamped / 2.0 - clamped * clamped * clamped / 24.0;
    }
}
