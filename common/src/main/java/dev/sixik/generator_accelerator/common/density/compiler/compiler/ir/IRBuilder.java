package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.McDensityFunctionClassNames;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.CompilingVisitor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.spline.SplineInliner;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import java.util.HashMap;
import java.util.Map;

/**
 * Walks a {@link DensityFunction} tree and produces a hash-consed {@link IRNode} DAG.
 *
 * <p>Three rules govern descent:
 * <ul>
 *   <li><b>Markers stop descent.</b> {@link DensityFunctions.Marker} (and its
 *       {@code MarkerOrMarked} interface) is a contract with {@code NoiseChunk}: it must
 *       round-trip so the chunk can wrap the inner subtree in a cell cache. We capture
 *       the entire marker as an extern and emit it verbatim — but the inner tree can
 *       still be compiled via {@link CompilingVisitor}, which the visitor cache will
 *       call recursively.</li>
 *   <li><b>Recognised nodes inline.</b> Every vanilla {@code DensityFunctions.*} record
 *       is destructured into the matching IR node, recursing into children so they too
 *       end up inline.</li>
 *   <li><b>Unrecognised nodes invoke.</b> Anything we don't recognise becomes
 *       {@link IRNode.Invoke}, captured by identity into the constant pool. The codegen
 *       calls it through {@code INVOKEINTERFACE DensityFunction.compute}.</li>
 * </ul>
 */
public final class IRBuilder {

    private final ConstantPool pool;
    private final CompilingVisitor outerVisitor;

    /** Hash-cons table — interns equal IR nodes to the same instance. */
    private final Map<IRNode, IRNode> intern = new HashMap<>();

    private long internRequests; // number of times intern() was called (pre-dedup count)

    public IRBuilder(ConstantPool pool, CompilingVisitor outerVisitor) {
        this.pool = pool;
        this.outerVisitor = outerVisitor;
    }

    public IRNode build(DensityFunction df) {
        return walk(df);
    }

    public int internedCount() {
        return intern.size();
    }

    /** Number of node-references collapsed by hash-consing (a CSE proxy metric). */
    public int cseSavings() {
        long delta = internRequests - intern.size();
        return delta < 0 ? 0 : (int) Math.min(delta, Integer.MAX_VALUE);
    }

    public ConstantPool pool() { return pool; }

    public IRNode intern(IRNode node) {
        node = canonicalizeCommutativeBin(node);
        internRequests++;
        IRNode existing = intern.get(node);
        if (existing != null) return existing;
        intern.put(node, node);
        return node;
    }

    /* --------------------------------------------------------------------- */
    /* Recognised vanilla nodes                                              */
    /* --------------------------------------------------------------------- */

    private IRNode walk(DensityFunction df) {
        // Marker boundary — capture the WHOLE marker as an extern, but ask the visitor
        // to compile its inner child first so the chunk-cache wrapper sees a Marker(type,
        // COMPILED). The visitor caches the repackaged Marker by source identity, so
        // multiple references to the same source marker collapse to a single extern slot
        // here (which keeps NoiseChunk's identity-based interpolator dedup tight).
        if (df instanceof DensityFunctions.MarkerOrMarked marker) {
            DensityFunction repackaged = outerVisitor.apply(marker);
            int idx = pool.internExtern(repackaged);
            if (repackaged instanceof DensityFunctions.Marker mm && cacheWrapperMarkerSupportsDirectRead(mm.type())) {
                pool.noteCacheWrapperFastPathExtern(idx);
            }
            return intern(new IRNode.Marker(idx));
        }

        // Holder indirection — peel and recurse. We do NOT compile the held DF as a
        // separate unit here; we want it inlined into the current bytecode so a router
        // field that's "holderHolder(barrierNoise)" generates one CompiledDF, not two.
        if (df instanceof DensityFunctions.HolderHolder hh) {
            return walk(hh.function().value());
        }

        // Pre-compiled CompiledDF — keep it opaque, do NOT re-compile or descend. This
        // happens when an outer compile feeds us the result of a child compile via the
        // visitor (e.g. a registry-warmed density_function that's already been compiled).
        if (df instanceof dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction) {
            int idx = pool.internExtern(df);
            return intern(new IRNode.Invoke(idx));
        }

        // Identity-significant sentinels that NoiseChunk swaps with chunk-bound
        // implementations during wrap. Wrapping these in a CompiledDF would break the
        // identity check and the chunk would hand back the no-op Blender output, which
        // collapses biome blending and surface beardifier carving.
        if (df instanceof DensityFunctions.BlendAlpha
                || df instanceof DensityFunctions.BlendOffset
                || df == DensityFunctions.BeardifierMarker.INSTANCE) {
            int idx = pool.internExtern(df);
            return intern(new IRNode.Invoke(idx));
        }

        if (df instanceof DensityFunctions.Constant c) {
            return intern(new IRNode.Const(c.value()));
        }

        if (df instanceof DensityFunctions.YClampedGradient g) {
            return intern(new IRNode.YClampedGradient(g.fromY(), g.toY(), g.fromValue(), g.toValue()));
        }

        if (df instanceof DensityFunctions.Clamp c) {
            return intern(new IRNode.Clamp(walk(c.input()), c.minValue(), c.maxValue()));
        }

        if (df instanceof DensityFunctions.RangeChoice rc) {
            return intern(new IRNode.RangeChoice(
                    walk(rc.input()), rc.minInclusive(), rc.maxExclusive(),
                    walk(rc.whenInRange()), walk(rc.whenOutOfRange())));
        }

        if (df instanceof DensityFunctions.MulOrAdd ma) {
            // MulOrAdd(arg, ADD) -> arg + constant; (arg, MUL) -> arg * constant.
            //
            // Short-circuit identity arguments at build time: a vanilla
            // TwoArgumentSimpleFunction.create(ADD/MUL, a, b) often degenerates into
            // MulOrAdd when one side is constant, and the constant is frequently the
            // identity (0 for ADD, 1 for MUL) — typically because a JSON template
            // adds an offset that some other layer left at 0. Folding here saves the
            // optimizer one rewrite pass and, more importantly, keeps the IR Bin
            // count down so RefCount/Splitter see a tighter graph.
            //
            // Note: Double.compare keeps -0.0 distinct from 0.0 — important because
            // (-0.0) + x is NOT always == x in IEEE 754 (it differs only at x = -0.0,
            // but that's enough to break parity).
            IRNode inputIr = walk(ma.input());
            double arg = ma.argument();
            DensityFunctions.MulOrAdd.Type type = ma.specificType();
            if (type == DensityFunctions.MulOrAdd.Type.ADD && Double.compare(arg, 0.0) == 0) {
                return inputIr;
            }
            if (type == DensityFunctions.MulOrAdd.Type.MUL && Double.compare(arg, 1.0) == 0) {
                return inputIr;
            }
            IRNode constIr = intern(new IRNode.Const(arg));
            IRNode.BinOp op = type == DensityFunctions.MulOrAdd.Type.ADD
                    ? IRNode.BinOp.ADD : IRNode.BinOp.MUL;
            return intern(new IRNode.Bin(op, inputIr, constIr));
        }

        if (df instanceof DensityFunctions.TwoArgumentSimpleFunction tas) {
            IRNode left  = walk(tas.argument1());
            IRNode right = walk(tas.argument2());
            IRNode.BinOp op = switch (tas.type()) {
                case ADD -> IRNode.BinOp.ADD;
                case MUL -> IRNode.BinOp.MUL;
                case MIN -> IRNode.BinOp.MIN;
                case MAX -> IRNode.BinOp.MAX;
            };
            return intern(new IRNode.Bin(op, left, right));
        }

        if (df instanceof DensityFunctions.Mapped m) {
            IRNode input = walk(m.input());
            IRNode.UnaryOp op = switch (m.type()) {
                case ABS -> IRNode.UnaryOp.ABS;
                case SQUARE -> IRNode.UnaryOp.SQUARE;
                case CUBE -> IRNode.UnaryOp.CUBE;
                case HALF_NEGATIVE -> IRNode.UnaryOp.HALF_NEGATIVE;
                case QUARTER_NEGATIVE -> IRNode.UnaryOp.QUARTER_NEGATIVE;
                case SQUEEZE -> IRNode.UnaryOp.SQUEEZE;
            };
            return intern(new IRNode.Unary(op, input));
        }

        if (df instanceof DensityFunctions.Noise n) {
            var noise = n.noise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            return intern(new IRNode.Noise(idx, n.xzScale(), n.yScale(), n.noise().maxValue()));
        }

        if (df instanceof DensityFunctions.ShiftedNoise sn) {
            var noise = sn.noise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            IRNode sx = walk(sn.shiftX());
            IRNode sy = walk(sn.shiftY());
            IRNode sz = walk(sn.shiftZ());
            return intern(new IRNode.ShiftedNoise(idx, sn.xzScale(), sn.yScale(), sx, sy, sz, sn.noise().maxValue()));
        }

        if (df instanceof DensityFunctions.ShiftA sa) {
            var noise = sa.offsetNoise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            return intern(new IRNode.ShiftA(idx, sa.offsetNoise().maxValue()));
        }
        if (df instanceof DensityFunctions.ShiftB sb) {
            var noise = sb.offsetNoise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            return intern(new IRNode.ShiftB(idx, sb.offsetNoise().maxValue()));
        }
        if (df instanceof DensityFunctions.Shift s) {
            var noise = s.offsetNoise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            return intern(new IRNode.Shift(idx, s.offsetNoise().maxValue()));
        }

        if (df instanceof DensityFunctions.WeirdScaledSampler wss) {
            var noise = wss.noise().noise();
            if (noise == null) return intern(new IRNode.Const(0.0));
            int idx = pool.internNoise(noise);
            IRNode input = walk(wss.input());
            return intern(new IRNode.WeirdScaled(input, idx, wss.rarityValueMapper().ordinal(),
                    wss.rarityValueMapper().ordinal() == 0
                            ? 2.0 * wss.noise().maxValue()
                            : 3.0 * wss.noise().maxValue()));
        }

        if (df instanceof DensityFunctions.BlendDensity bd) {
            return intern(new IRNode.BlendDensity(walk(bd.input())));
        }

        if (df instanceof DensityFunctions.Spline splineDf) {
            return new SplineInliner(this).inline(splineDf.spline());
        }

        if (df instanceof BlendedNoise blended && df.getClass() == BlendedNoise.class) {
            int specIdx = pool.internBlendedNoiseSpec(blended);
            if (specIdx >= 0) {
                return intern(new IRNode.InlinedBlendedNoise(specIdx, blended.maxValue()));
            }
            int idx = pool.internExtern(df);
            return intern(new IRNode.Invoke(idx));
        }

        if (McDensityFunctionClassNames.DENSITY_FUNCTIONS_END_ISLAND.equals(df.getClass().getName())) {
            int idx = pool.internExtern(df);
            return intern(new IRNode.EndIslands(idx));
        }

        if (df instanceof Beardifier) {
            int idx = pool.internExtern(df);
            return intern(new IRNode.Beardifier(idx));
        }

        // BlendAlpha/BlendOffset/Beardifier, unknown mod / datapack DFs, etc.
        int idx = pool.internExtern(df);
        return intern(new IRNode.Invoke(idx));
    }

    /** Helper used by {@link SplineInliner} to walk a spline coordinate's underlying DF. */
    public IRNode walkChild(DensityFunction df) {
        return walk(df);
    }

    private static boolean cacheWrapperMarkerSupportsDirectRead(DensityFunctions.Marker.Type type) {
        return type == DensityFunctions.Marker.Type.Cache2D
                || type == DensityFunctions.Marker.Type.FlatCache
                || type == DensityFunctions.Marker.Type.CacheOnce
                || type == DensityFunctions.Marker.Type.CacheAllInCell;
    }

    private static IRNode canonicalizeCommutativeBin(IRNode node) {
        if (!(node instanceof IRNode.Bin bin) || !isSafelyCommutative(bin.op())) {
            return node;
        }
        if (!isPureForCanonicalization(bin.left()) || !isPureForCanonicalization(bin.right())) {
            return node;
        }
        if (structuralKey(bin.right()).compareTo(structuralKey(bin.left())) < 0) {
            return new IRNode.Bin(bin.op(), bin.right(), bin.left());
        }
        return node;
    }

    private static boolean isSafelyCommutative(IRNode.BinOp op) {
        return op == IRNode.BinOp.ADD || op == IRNode.BinOp.MUL;
    }

    private static boolean isPureForCanonicalization(IRNode node) {
        if (node instanceof IRNode.Const
                || node instanceof IRNode.BlockX
                || node instanceof IRNode.BlockY
                || node instanceof IRNode.BlockZ
                || node instanceof IRNode.YClampedGradient) {
            return true;
        }
        if (node instanceof IRNode.Unary unary) {
            return isPureForCanonicalization(unary.input());
        }
        if (node instanceof IRNode.Bin bin) {
            return isPureForCanonicalization(bin.left()) && isPureForCanonicalization(bin.right());
        }
        if (node instanceof IRNode.Clamp clamp) {
            return isPureForCanonicalization(clamp.input());
        }
        if (node instanceof IRNode.RangeChoice rc) {
            return isPureForCanonicalization(rc.input())
                    && isPureForCanonicalization(rc.whenInRange())
                    && isPureForCanonicalization(rc.whenOutOfRange());
        }
        return false;
    }

    private static String structuralKey(IRNode node) {
        if (node instanceof IRNode.Const c) {
            return "Const:" + Long.toUnsignedString(Double.doubleToRawLongBits(c.value()), 16);
        }
        if (node instanceof IRNode.BlockX) return "BlockX";
        if (node instanceof IRNode.BlockY) return "BlockY";
        if (node instanceof IRNode.BlockZ) return "BlockZ";
        if (node instanceof IRNode.YClampedGradient g) {
            return "YClampedGradient:"
                    + g.fromY() + ':'
                    + g.toY() + ':'
                    + Long.toUnsignedString(Double.doubleToRawLongBits(g.fromValue()), 16) + ':'
                    + Long.toUnsignedString(Double.doubleToRawLongBits(g.toValue()), 16);
        }
        if (node instanceof IRNode.Unary unary) {
            return "Unary:" + unary.op().name() + '(' + structuralKey(unary.input()) + ')';
        }
        if (node instanceof IRNode.Bin bin) {
            return "Bin:" + bin.op().name() + '(' + structuralKey(bin.left()) + ',' + structuralKey(bin.right()) + ')';
        }
        if (node instanceof IRNode.Clamp clamp) {
            return "Clamp:"
                    + Long.toUnsignedString(Double.doubleToRawLongBits(clamp.min()), 16) + ':'
                    + Long.toUnsignedString(Double.doubleToRawLongBits(clamp.max()), 16)
                    + '(' + structuralKey(clamp.input()) + ')';
        }
        if (node instanceof IRNode.RangeChoice rc) {
            return "RangeChoice:"
                    + Long.toUnsignedString(Double.doubleToRawLongBits(rc.min()), 16) + ':'
                    + Long.toUnsignedString(Double.doubleToRawLongBits(rc.max()), 16)
                    + '(' + structuralKey(rc.input()) + ','
                    + structuralKey(rc.whenInRange()) + ','
                    + structuralKey(rc.whenOutOfRange()) + ')';
        }
        throw new IllegalArgumentException("No canonical key for impure IR node " + node);
    }
}
