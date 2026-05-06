package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Compiler pass that rewrites the legacy noise IR nodes into the unrolled
 * {@link IRNode.InlinedNoise} (and its {@link IRNode.WeirdRarity} sibling for
 * {@code WeirdScaledSampler}) form before codegen. Runs after the first
 * {@link IROptimizer} pass — that pass folds away constant coordinates and
 * algebraic identities, leaving the noise call sites with their real coordinate
 * sub-expressions exposed for {@link RefCount} to count and {@code Splitter} to
 * extract.
 *
 * <p>Why a separate pass instead of having {@link IRBuilder} emit inlined nodes
 * directly:
 * <ul>
 *   <li>{@link IROptimizer}'s constant-folding rules never see the per-noise
 *       coordinate expressions until they're materialised as {@link IRNode.Bin}
 *       nodes — emitting {@link IRNode.InlinedNoise} during build would skip
 *       opportunities like {@code (x*xz + 0)} folding to just {@code (x*xz)}.</li>
 *   <li>Separating the pass keeps the legacy {@link IRNode.Noise} / {@link
 *       IRNode.ShiftedNoise} / {@link IRNode.ShiftA} / {@link IRNode.ShiftB} /
 *       {@link IRNode.Shift} / {@link IRNode.WeirdScaled} forms available as a
 *       fallback when {@link
 *       dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool#internNoiseSpec}
 *       returns {@code -1} (mixin binding failure for the underlying
 *       NormalNoise).</li>
 *   <li>Surfacing the coordinate expressions through {@link IROptimizer} a
 *       second time after expansion lets shared-coordinate CSE happen — two
 *       inlined noises that both want {@code x} (no scaling, raw block coord)
 *       get to share the same {@link IRNode.BlockX} node, which was already
 *       interned once.</li>
 * </ul>
 *
 * <p>The walker is memoized by IRNode identity so each interned subtree is
 * rewritten exactly once even when it appears under many parents.
 *
 * <p>{@link Result#noisesSpecialized} counts the number of distinct NormalNoise
 * instances we successfully inlined; {@link Result#octavesUnrolled} counts the
 * total active octaves emitted across all inlined sites. A fallback noise (one
 * whose {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpecCache}
 * lookup returned {@code null}) leaves the original IR node in place and does
 * not contribute to either counter.
 */
public final class NoiseExpander {

    public record Result(IRNode root, int noisesSpecialized, int octavesUnrolled) {}

    private final IRBuilder builder;
    private final ConstantPool pool;
    private final IdentityHashMap<IRNode, IRNode> memo = new IdentityHashMap<>();
    /** Track which spec pool indices we've already counted toward "noisesSpecialized"
     *  so a single NormalNoise referenced from N sites doesn't inflate the metric. */
    private final java.util.HashSet<Integer> countedSpecs = new java.util.HashSet<>();
    private int octavesUnrolled = 0;

    private NoiseExpander(IRBuilder builder, ConstantPool pool) {
        this.builder = builder;
        this.pool = pool;
    }

    public static Result expand(IRNode root, IRBuilder builder, ConstantPool pool) {
        NoiseExpander ex = new NoiseExpander(builder, pool);
        IRNode rewritten = ex.walk(root);
        return new Result(rewritten, ex.countedSpecs.size(), ex.octavesUnrolled);
    }

    private IRNode intern(IRNode n) { return builder.intern(n); }

    private IRNode walk(IRNode node) {
        IRNode cached = memo.get(node);
        if (cached != null) return cached;
        IRNode walked = walkChildren(node);
        IRNode rewritten = expand(walked);
        memo.put(node, rewritten);
        return rewritten;
    }

    /* --------------------------------------------------------------------- */
    /* Structural recursion (mirrors IROptimizer.walkChildren)               */
    /* --------------------------------------------------------------------- */

    private IRNode walkChildren(IRNode node) {
        return switch (node) {
            case IRNode.Bin bin -> {
                IRNode l = walk(bin.left());
                IRNode r = walk(bin.right());
                yield (l == bin.left() && r == bin.right())
                        ? bin
                        : intern(new IRNode.Bin(bin.op(), l, r));
            }
            case IRNode.Unary u -> {
                IRNode in = walk(u.input());
                yield in == u.input() ? u : intern(new IRNode.Unary(u.op(), in));
            }
            case IRNode.Clamp cl -> {
                IRNode in = walk(cl.input());
                yield in == cl.input() ? cl : intern(new IRNode.Clamp(in, cl.min(), cl.max()));
            }
            case IRNode.RangeChoice rc -> {
                IRNode in = walk(rc.input());
                IRNode wir = walk(rc.whenInRange());
                IRNode wor = walk(rc.whenOutOfRange());
                yield (in == rc.input() && wir == rc.whenInRange() && wor == rc.whenOutOfRange())
                        ? rc
                        : intern(new IRNode.RangeChoice(in, rc.min(), rc.max(), wir, wor));
            }
            case IRNode.ShiftedNoise sn -> {
                IRNode sx = walk(sn.shiftX());
                IRNode sy = walk(sn.shiftY());
                IRNode sz = walk(sn.shiftZ());
                yield (sx == sn.shiftX() && sy == sn.shiftY() && sz == sn.shiftZ())
                        ? sn
                        : intern(new IRNode.ShiftedNoise(sn.noiseIndex(), sn.xzScale(), sn.yScale(),
                                sx, sy, sz, sn.maxValue()));
            }
            case IRNode.WeirdScaled w -> {
                IRNode in = walk(w.input());
                yield in == w.input()
                        ? w
                        : intern(new IRNode.WeirdScaled(in, w.noiseIndex(),
                                w.rarityValueMapperOrdinal(), w.maxValue()));
            }
            case IRNode.Spline.Multipoint mp -> {
                IRNode coord = walk(mp.coordinate());
                List<IRNode.Spline> values = new ArrayList<>(mp.values().size());
                boolean valuesChanged = false;
                for (IRNode.Spline v : mp.values()) {
                    IRNode rewritten = walk(v);
                    values.add((IRNode.Spline) rewritten);
                    if (rewritten != v) valuesChanged = true;
                }
                yield (coord == mp.coordinate() && !valuesChanged)
                        ? mp
                        : intern(new IRNode.Spline.Multipoint(coord, mp.locations(),
                                values, mp.derivatives(), mp.minValue(), mp.maxValue()));
            }
            case IRNode.BlendDensity bd -> {
                IRNode in = walk(bd.input());
                yield in == bd.input() ? bd : intern(new IRNode.BlendDensity(in));
            }
            case IRNode.InlinedNoise n -> {
                IRNode cx = walk(n.coordX());
                IRNode cy = walk(n.coordY());
                IRNode cz = walk(n.coordZ());
                yield (cx == n.coordX() && cy == n.coordY() && cz == n.coordZ())
                        ? n
                        : intern(new IRNode.InlinedNoise(n.specPoolIndex(), cx, cy, cz, n.maxValue()));
            }
            case IRNode.WeirdRarity wr -> {
                IRNode in = walk(wr.input());
                yield in == wr.input() ? wr
                        : intern(new IRNode.WeirdRarity(in, wr.rarityValueMapperOrdinal()));
            }
            default -> node;
        };
    }

    /* --------------------------------------------------------------------- */
    /* The actual rewrite                                                    */
    /* --------------------------------------------------------------------- */

    private IRNode expand(IRNode node) {
        return switch (node) {
            case IRNode.Noise n -> tryInline(n);
            case IRNode.ShiftedNoise sn -> tryInline(sn);
            case IRNode.ShiftA sa -> tryInline(sa);
            case IRNode.ShiftB sb -> tryInline(sb);
            case IRNode.Shift s -> tryInline(s);
            case IRNode.WeirdScaled w -> tryInline(w);
            default -> node;
        };
    }

    /* ---------------- Noise / ShiftedNoise / ShiftA / ShiftB / Shift ---------------- */

    private IRNode tryInline(IRNode.Noise n) {
        var noise = pool.noise(n.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return n;
        countSpec(specIdx);
        // Coordinates: (x*xzScale, y*yScale, z*xzScale).
        IRNode cx = scale(blockX(), n.xzScale());
        IRNode cy = scale(blockY(), n.yScale());
        IRNode cz = scale(blockZ(), n.xzScale());
        return intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, n.maxValue()));
    }

    private IRNode tryInline(IRNode.ShiftedNoise sn) {
        var noise = pool.noise(sn.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return sn;
        countSpec(specIdx);
        // Coordinates: (x*xzScale + shiftX, y*yScale + shiftY, z*xzScale + shiftZ).
        IRNode cx = add(scale(blockX(), sn.xzScale()), sn.shiftX());
        IRNode cy = add(scale(blockY(), sn.yScale()), sn.shiftY());
        IRNode cz = add(scale(blockZ(), sn.xzScale()), sn.shiftZ());
        return intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, sn.maxValue()));
    }

    private IRNode tryInline(IRNode.ShiftA sa) {
        var noise = pool.noise(sa.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return sa;
        countSpec(specIdx);
        // ShiftA: noise.getValue(x*0.25, 0.0, z*0.25) * 4.0
        IRNode cx = scale(blockX(), 0.25);
        IRNode cy = constant(0.0);
        IRNode cz = scale(blockZ(), 0.25);
        IRNode inlined = intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, sa.maxValue()));
        return intern(new IRNode.Bin(IRNode.BinOp.MUL, inlined, constant(4.0)));
    }

    private IRNode tryInline(IRNode.ShiftB sb) {
        var noise = pool.noise(sb.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return sb;
        countSpec(specIdx);
        // ShiftB: noise.getValue(z*0.25, x*0.25, 0.0) * 4.0  (note swapped axes)
        IRNode cx = scale(blockZ(), 0.25);
        IRNode cy = scale(blockX(), 0.25);
        IRNode cz = constant(0.0);
        IRNode inlined = intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, sb.maxValue()));
        return intern(new IRNode.Bin(IRNode.BinOp.MUL, inlined, constant(4.0)));
    }

    private IRNode tryInline(IRNode.Shift s) {
        var noise = pool.noise(s.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return s;
        countSpec(specIdx);
        // Shift: noise.getValue(x*0.25, y*0.25, z*0.25) * 4.0
        IRNode cx = scale(blockX(), 0.25);
        IRNode cy = scale(blockY(), 0.25);
        IRNode cz = scale(blockZ(), 0.25);
        IRNode inlined = intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, s.maxValue()));
        return intern(new IRNode.Bin(IRNode.BinOp.MUL, inlined, constant(4.0)));
    }

    /* ---------------- WeirdScaledSampler ---------------- */

    private IRNode tryInline(IRNode.WeirdScaled w) {
        var noise = pool.noise(w.noiseIndex());
        int specIdx = pool.internNoiseSpec(noise);
        if (specIdx < 0) return w;
        countSpec(specIdx);
        // Form: abs(noise.getValue(x/r, y/r, z/r)) * r,  where r = weirdRarity(input, ordinal).
        // We surface the rarity factor as a standalone IRNode so RefCount sees a single
        // shared materialisation across the three coordinate divides AND the post-noise
        // multiplication (4 uses total). The optimizer can later turn x / r into x * (1/r)
        // if r happens to fold to a constant — which it never does at compile time, since
        // r depends on the runtime input — but the inversion still happens once at
        // codegen via DSTORE/DLOAD-from-slot.
        IRNode rarity = intern(new IRNode.WeirdRarity(w.input(), w.rarityValueMapperOrdinal()));
        IRNode cx = intern(new IRNode.Bin(IRNode.BinOp.DIV, blockX(), rarity));
        IRNode cy = intern(new IRNode.Bin(IRNode.BinOp.DIV, blockY(), rarity));
        IRNode cz = intern(new IRNode.Bin(IRNode.BinOp.DIV, blockZ(), rarity));
        // The noise's "max value" used here is the per-call maxValue (not w.maxValue()
        // which is already 2x or 3x). We preserve symmetry by using the per-NormalNoise
        // max value directly: it's stored in the spec implicitly as
        // `first.maxValue + second.maxValue` * valueFactor — but we don't have that
        // surfaced, so we use the weirdscaled maxValue divided by rarity-mapper factor.
        // Concretely the upstream IRNode.WeirdScaled.maxValue is `factor * noiseMax`
        // where factor ∈ {2.0, 3.0}; recovering noiseMax = maxValue / factor.
        double factor = w.rarityValueMapperOrdinal() == 0 ? 2.0 : 3.0;
        IRNode inlined = intern(new IRNode.InlinedNoise(specIdx, cx, cy, cz, w.maxValue() / factor));
        IRNode absNoise = intern(new IRNode.Unary(IRNode.UnaryOp.ABS, inlined));
        return intern(new IRNode.Bin(IRNode.BinOp.MUL, absNoise, rarity));
    }

    /* ---------------- IR builders ---------------- */

    private IRNode blockX() { return intern(IRNode.BlockX.INSTANCE); }
    private IRNode blockY() { return intern(IRNode.BlockY.INSTANCE); }
    private IRNode blockZ() { return intern(IRNode.BlockZ.INSTANCE); }

    private IRNode constant(double v) { return intern(new IRNode.Const(v)); }

    /**
     * Multiply {@code coord} by {@code scale}; collapses {@code scale == 1.0} to the
     * coordinate untouched and {@code scale == 0.0} to the constant {@code 0.0}.
     * The post-expansion {@link IROptimizer} pass would do this anyway — folding
     * here keeps the intermediate IR smaller and makes {@code RefCount} see fewer
     * one-shot Bin nodes.
     */
    private IRNode scale(IRNode coord, double scale) {
        if (Double.compare(scale, 1.0) == 0) return coord;
        if (Double.compare(scale, 0.0) == 0) return constant(0.0);
        return intern(new IRNode.Bin(IRNode.BinOp.MUL, coord, constant(scale)));
    }

    private IRNode add(IRNode left, IRNode right) {
        // Same shrink-on-identity trick as scale().
        if (right instanceof IRNode.Const c && Double.compare(c.value(), 0.0) == 0) return left;
        if (left instanceof IRNode.Const c && Double.compare(c.value(), 0.0) == 0) return right;
        return intern(new IRNode.Bin(IRNode.BinOp.ADD, left, right));
    }

    private void countSpec(int specIdx) {
        if (countedSpecs.add(specIdx)) {
            octavesUnrolled += pool.noiseSpec(specIdx).totalActiveOctaves();
        }
    }
}
