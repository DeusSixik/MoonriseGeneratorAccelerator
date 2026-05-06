package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixpoint peephole rewriter that runs after {@link IRBuilder#build} and before
 * {@link Bounds} / {@link RefCount} / {@code Splitter}. Every rewritten node is
 * routed back through {@link IRBuilder#intern} so hash-consing / CSE stay
 * consistent and the downstream {@link RefCount} sees the post-rewrite topology.
 *
 * <p>The pass deliberately only does rewrites that are bit-for-bit equivalent
 * with the vanilla evaluator on every double input — no floating-point
 * reassociation, no {@code -0.0} sloppiness, no algebraic identities that
 * silently swap an Infinity for a NaN. The semantics for each constant fold
 * mirror the corresponding case in {@code Codegen.emitBin}/{@code emitUnary}
 * and {@code Bounds.unaryInterval}, so a folded {@code Const} is the exact
 * value that would have been pushed at runtime by the un-optimised bytecode.
 *
 * <p>Strength reduction is gated by a small structural cost model plus a
 * refcount snapshot taken at the start of each iteration: a node is "cheap to
 * duplicate" only if it's a literal/coordinate leaf or the codegen will spill
 * it anyway because it's already shared. This keeps {@code x*2 -> x+x} from
 * blowing up bytecode size when {@code x} is a deep arithmetic chain that
 * isn't otherwise CSE-shared.
 *
 * <p>Further shrinking per-root IR and {@code /dfc stats} “unique node” totals is
 * mostly about more parity-safe peepholes, fewer {@link IRNode.Invoke} leaves for
 * mod types, and (only with a proof of identical evaluation) commutative
 * normalisation in {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRBuilder#intern}.
 */
public final class IROptimizer {

    /** Hard upper bound on fixpoint iterations; every rule strictly shrinks IR or
     *  is idempotent so we converge in 1-3 iterations on real routers. The cap is
     *  defensive in case a future rule oscillates. */
    public static final int MAX_ITERATIONS = 16;

    /** Structural-cost ceiling under which a node is considered "free to dup" for
     *  strength reduction. 1 = literal leaves only (Const, BlockX/Y/Z); duplicating
     *  any larger expression is gated through the refcount-shared escape hatch in
     *  {@link #cheap}. */
    private static final int CHEAP_LEAF_COST = 1;

    /** Optimizer output. {@code rewrites} is the number of fixpoint iterations
     *  that produced a non-identity rewrite — usually 1 for routers without
     *  trivially-foldable subtrees, up to {@link #MAX_ITERATIONS} in pathological
     *  cases. Useful as an "is this pass earning its keep" diagnostic. */
    public record Result(IRNode root, int rewrites) {}

    private final IRBuilder builder;
    private final ConstantPool pool;
    private IdentityHashMap<IRNode, IRNode> memo;
    private boolean changed;
    /** Snapshot of refcounts at the start of the current iteration. Re-taken each
     *  iteration because rewrites can change which nodes are shared. */
    private Map<IRNode, Integer> refSnapshot;

    private IROptimizer(IRBuilder builder, ConstantPool pool) {
        this.builder = builder;
        this.pool = pool;
    }

    public static Result optimize(IRNode root, IRBuilder builder, ConstantPool pool) {
        IROptimizer opt = new IROptimizer(builder, pool);
        int iterationsThatRewrote = 0;
        // One shared ref map for all fixpoint iterations: RefCount reuses the same
        // IdentityHashMap (cleared per compute) so we do not allocate O(iterations)
        // maps on large DAGs.
        var refWs = new RefCount.Workspace();
        RefCount.Result rc = RefCount.compute(refWs, root);
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            opt.refSnapshot = rc.refs();
            opt.memo = new IdentityHashMap<>();
            opt.changed = false;
            IRNode next = opt.rewrite(root);
            if (!opt.changed) {
                return new Result(root, iterationsThatRewrote);
            }
            iterationsThatRewrote++;
            root = next;
            rc = RefCount.compute(refWs, root);
        }
        return new Result(root, iterationsThatRewrote);
    }

    private IRNode intern(IRNode n) {
        return builder.intern(n);
    }

    private IRNode rewrite(IRNode node) {
        IRNode cached = memo.get(node);
        if (cached != null) return cached;
        IRNode walked = walkChildren(node);
        IRNode rewritten = peephole(walked);
        if (rewritten != node) changed = true;
        memo.put(node, rewritten);
        return rewritten;
    }

    /* --------------------------------------------------------------------- */
    /* Structural walk — rebuild parent only when at least one child changed */
    /* --------------------------------------------------------------------- */

    private IRNode walkChildren(IRNode node) {
        return switch (node) {
            case IRNode.Bin bin -> {
                IRNode l = rewrite(bin.left());
                IRNode r = rewrite(bin.right());
                yield (l == bin.left() && r == bin.right())
                        ? bin
                        : intern(new IRNode.Bin(bin.op(), l, r));
            }
            case IRNode.Unary u -> {
                IRNode in = rewrite(u.input());
                yield in == u.input() ? u : intern(new IRNode.Unary(u.op(), in));
            }
            case IRNode.Clamp cl -> {
                IRNode in = rewrite(cl.input());
                yield in == cl.input() ? cl : intern(new IRNode.Clamp(in, cl.min(), cl.max()));
            }
            case IRNode.RangeChoice rc -> {
                IRNode in = rewrite(rc.input());
                IRNode wir = rewrite(rc.whenInRange());
                IRNode wor = rewrite(rc.whenOutOfRange());
                yield (in == rc.input() && wir == rc.whenInRange() && wor == rc.whenOutOfRange())
                        ? rc
                        : intern(new IRNode.RangeChoice(in, rc.min(), rc.max(), wir, wor));
            }
            case IRNode.ShiftedNoise sn -> {
                IRNode sx = rewrite(sn.shiftX());
                IRNode sy = rewrite(sn.shiftY());
                IRNode sz = rewrite(sn.shiftZ());
                yield (sx == sn.shiftX() && sy == sn.shiftY() && sz == sn.shiftZ())
                        ? sn
                        : intern(new IRNode.ShiftedNoise(sn.noiseIndex(), sn.xzScale(), sn.yScale(),
                                sx, sy, sz, sn.maxValue()));
            }
            case IRNode.WeirdScaled w -> {
                IRNode in = rewrite(w.input());
                yield in == w.input()
                        ? w
                        : intern(new IRNode.WeirdScaled(in, w.noiseIndex(),
                                w.rarityValueMapperOrdinal(), w.maxValue()));
            }
            case IRNode.InlinedNoise n -> {
                IRNode cx = rewrite(n.coordX());
                IRNode cy = rewrite(n.coordY());
                IRNode cz = rewrite(n.coordZ());
                yield (cx == n.coordX() && cy == n.coordY() && cz == n.coordZ())
                        ? n
                        : intern(new IRNode.InlinedNoise(n.specPoolIndex(), cx, cy, cz, n.maxValue()));
            }
            case IRNode.WeirdRarity wr -> {
                IRNode in = rewrite(wr.input());
                yield in == wr.input() ? wr
                        : intern(new IRNode.WeirdRarity(in, wr.rarityValueMapperOrdinal()));
            }
            case IRNode.Spline.Multipoint mp -> {
                IRNode coord = rewrite(mp.coordinate());
                List<IRNode.Spline> values = new ArrayList<>(mp.values().size());
                boolean valuesChanged = false;
                for (IRNode.Spline v : mp.values()) {
                    IRNode rewritten = rewrite(v);
                    // The spline value rewrite paths only ever produce Spline nodes
                    // (Constant stays Constant, Multipoint stays Multipoint), so this
                    // cast is safe — see the rewriter's no-rules-for-Spline policy in
                    // peephole(). If a future rule breaks that invariant the cast will
                    // ClassCastException loudly rather than silently corrupt the IR.
                    values.add((IRNode.Spline) rewritten);
                    if (rewritten != v) valuesChanged = true;
                }
                yield (coord == mp.coordinate() && !valuesChanged)
                        ? mp
                        : intern(new IRNode.Spline.Multipoint(coord, mp.locations(),
                                values, mp.derivatives(), mp.minValue(), mp.maxValue()));
            }
            case IRNode.BlendDensity bd -> {
                IRNode in = rewrite(bd.input());
                yield in == bd.input() ? bd : intern(new IRNode.BlendDensity(in));
            }
            // Leaves and opaque-extern nodes — nothing to descend into.
            default -> node;
        };
    }

    /* --------------------------------------------------------------------- */
    /* Peephole rewrite dispatch                                             */
    /* --------------------------------------------------------------------- */

    private IRNode peephole(IRNode node) {
        return switch (node) {
            case IRNode.Bin bin -> peepholeBin(bin);
            case IRNode.Unary u -> peepholeUnary(u);
            case IRNode.Clamp cl -> peepholeClamp(cl);
            case IRNode.RangeChoice rc -> peepholeRangeChoice(rc);
            default -> node;
        };
    }

    private IRNode peepholeBin(IRNode.Bin bin) {
        IRNode l = bin.left();
        IRNode r = bin.right();

        // Constant folding: both operands are literals. Same arithmetic semantics
        // as Codegen.emitBin so the result is bit-identical to what the un-folded
        // bytecode would have computed.
        if (l instanceof IRNode.Const cl && r instanceof IRNode.Const cr) {
            double a = cl.value();
            double b = cr.value();
            return intern(new IRNode.Const(switch (bin.op()) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> a / b;
                case MIN -> Math.min(a, b);
                case MAX -> Math.max(a, b);
            }));
        }

        // min(x, c) / max(x, c) (and const on the left) with one constant arm: if
        // Bounds prove the non-constant side is always on one side of c, the
        // result is bit-identical to Math.min / Math.max on the un-optimized
        // bytecode (same left-to-right eval as Codegen.emitBin).
        if (bin.op() == IRNode.BinOp.MIN) {
            IRNode n = peepholeMinWithOneConst(l, r);
            if (n != null) return n;
        } else if (bin.op() == IRNode.BinOp.MAX) {
            IRNode n = peepholeMaxWithOneConst(l, r);
            if (n != null) return n;
        }

        switch (bin.op()) {
            case ADD -> {
                if (isConst(l, 0.0)) return r;
                if (isConst(r, 0.0)) return l;
            }
            case SUB -> {
                if (isConst(r, 0.0)) return l;
                if (isConst(l, 0.0)) return intern(new IRNode.Unary(IRNode.UnaryOp.NEG, r));
            }
            case MUL -> {
                if (isConst(l, 0.0) || isConst(r, 0.0)) return intern(new IRNode.Const(0.0));
                if (isConst(l, 1.0)) return r;
                if (isConst(r, 1.0)) return l;
                if (isConst(l, -1.0)) return intern(new IRNode.Unary(IRNode.UnaryOp.NEG, r));
                if (isConst(r, -1.0)) return intern(new IRNode.Unary(IRNode.UnaryOp.NEG, l));
                // Strength reduction: x * 2 -> x + x. Only when duplicating the
                // non-constant operand is free — i.e. it's a literal/coordinate leaf
                // (single-instruction re-emit, never spilled) or it's already shared
                // (refcount >= 2 -> codegen spills once and both uses are DLOAD).
                if (isConst(r, 2.0) && cheap(l)) return intern(new IRNode.Bin(IRNode.BinOp.ADD, l, l));
                if (isConst(l, 2.0) && cheap(r)) return intern(new IRNode.Bin(IRNode.BinOp.ADD, r, r));
            }
            case DIV -> {
                if (isConst(r, 1.0)) return l;
                // x / c -> x * (1/c). DMUL is one cycle on every JVM, DDIV is 8-20.
                // Guarded against c being non-finite, zero, or a value whose
                // reciprocal isn't exactly representable — the round-trip check
                // (1.0 / inv) == c rejects rewrites that would shift any sample
                // by a ULP and break parity.
                if (r instanceof IRNode.Const rc) {
                    double c = rc.value();
                    if (Double.isFinite(c) && c != 0.0) {
                        double inv = 1.0 / c;
                        if (Double.isFinite(inv) && (1.0 / inv) == c) {
                            return intern(new IRNode.Bin(IRNode.BinOp.MUL, l,
                                    intern(new IRNode.Const(inv))));
                        }
                    }
                }
            }
            case MIN, MAX -> {
                // After interning, structurally-identical operands share identity.
                if (l == r) return l;
            }
        }
        return bin;
    }

    private IRNode peepholeUnary(IRNode.Unary u) {
        IRNode in = u.input();

        // Constant folding. Each branch mirrors the corresponding emit case so the
        // folded constant matches the runtime exactly. SQUEEZE delegates to
        // Bounds.squeeze (which is the same closed form as Runtime.squeeze).
        if (in instanceof IRNode.Const c) {
            double v = c.value();
            double folded = switch (u.op()) {
                case ABS -> Math.abs(v);
                case NEG -> -v;
                case SQUARE -> v * v;
                case CUBE -> v * v * v;
                case HALF_NEGATIVE -> v > 0.0 ? v : v * 0.5;
                case QUARTER_NEGATIVE -> v > 0.0 ? v : v * 0.25;
                case SQUEEZE -> Bounds.squeeze(v);
            };
            return intern(new IRNode.Const(folded));
        }

        // Nested-unary collapses. abs(square(x)) is valid because square(NaN) = NaN
        // and abs(NaN) = NaN, so the IEEE behaviour is preserved.
        if (in instanceof IRNode.Unary inner) {
            switch (u.op()) {
                case ABS -> {
                    if (inner.op() == IRNode.UnaryOp.ABS) return inner;
                    if (inner.op() == IRNode.UnaryOp.NEG) {
                        return intern(new IRNode.Unary(IRNode.UnaryOp.ABS, inner.input()));
                    }
                    if (inner.op() == IRNode.UnaryOp.SQUARE) return inner;
                }
                case NEG -> {
                    if (inner.op() == IRNode.UnaryOp.NEG) return inner.input();
                }
                case SQUARE -> {
                    if (inner.op() == IRNode.UnaryOp.NEG || inner.op() == IRNode.UnaryOp.ABS) {
                        return intern(new IRNode.Unary(IRNode.UnaryOp.SQUARE, inner.input()));
                    }
                }
                default -> { /* no nested-unary identities for the others */ }
            }
        }
        return u;
    }

    private IRNode peepholeClamp(IRNode.Clamp cl) {
        // Constant input -> evaluate the clamp at compile time.
        if (cl.input() instanceof IRNode.Const c) {
            double v = Math.max(cl.min(), Math.min(cl.max(), c.value()));
            return intern(new IRNode.Const(v));
        }
        // Degenerate range: vanilla Clamp.compute returns max(min, min(max, v)),
        // and when min >= max the outer Math.max forces the result to min for any
        // v. That collapse is safe for every input.
        if (cl.min() >= cl.max()) {
            return intern(new IRNode.Const(cl.min()));
        }
        // If Bounds prove every value of the input is already in [min, max], the
        // clamp is a no-op (vanilla: max(min, min(v, max)) = v for v in range).
        try {
            double[] iv = Bounds.interval(cl.input(), pool);
            if (Double.isFinite(iv[0]) && Double.isFinite(iv[1])
                    && iv[0] >= cl.min() && iv[1] <= cl.max()) {
                return cl.input();
            }
        } catch (RuntimeException ignore) {
        }
        // Coalesce nested clamps: Clamp(Clamp(x, a, b), c, d) is just the
        // intersection of the two intervals. If they don't overlap the inner
        // clamp's output is fully outside the outer one and the result collapses
        // to a constant (the outer min, which is what max(min, ...) returns).
        if (cl.input() instanceof IRNode.Clamp inner) {
            double newMin = Math.max(cl.min(), inner.min());
            double newMax = Math.min(cl.max(), inner.max());
            if (newMin >= newMax) return intern(new IRNode.Const(newMin));
            return intern(new IRNode.Clamp(inner.input(), newMin, newMax));
        }
        return cl;
    }

    private IRNode peepholeRangeChoice(IRNode.RangeChoice rc) {
        // Both arms identical -> the branch is a no-op, drop it.
        if (rc.whenInRange() == rc.whenOutOfRange()) {
            return rc.whenInRange();
        }
        // Bounds-driven short-circuit. If the input's interval-arithmetic bounds
        // prove it can never leave (or always leaves) [min, max), one arm becomes
        // dead. Bounds.interval can throw for opaque externs without finite
        // bounds; in that case leave the RangeChoice intact.
        try {
            double[] iv = Bounds.interval(rc.input(), pool);
            double lo = iv[0];
            double hi = iv[1];
            if (Double.isFinite(lo) && Double.isFinite(hi)) {
                // Always out of [min, max): every value is below min or at-or-above max.
                if (hi < rc.min() || lo >= rc.max()) {
                    return rc.whenOutOfRange();
                }
                // Always in [min, max) (note: half-open, so hi == max is OUT).
                if (lo >= rc.min() && hi < rc.max()) {
                    return rc.whenInRange();
                }
            }
        } catch (RuntimeException ex) {
            // Bounds bailout — keep the node as-is and let runtime evaluate.
        }
        return rc;
    }

    /**
     * {@code min(x, c)} or {@code min(c, x)} with exactly one constant (the other
     * side not a {@link IRNode.Const} so the two-const fold above does not apply).
     * Folds to {@code x} or {@code c} when {@link Bounds#interval} proves the
     * whole range of {@code x} is on one side of {@code c}, matching
     * {@code Math.min} in {@code Codegen.emitBin}.
     */
    private IRNode peepholeMinWithOneConst(IRNode l, IRNode r) {
        if (l instanceof IRNode.Const cl && !(r instanceof IRNode.Const)) {
            return foldMinConstAndExpr(cl.value(), r);
        }
        if (r instanceof IRNode.Const cr && !(l instanceof IRNode.Const)) {
            return foldMinConstAndExpr(cr.value(), l);
        }
        return null;
    }

    private IRNode foldMinConstAndExpr(double c, IRNode x) {
        try {
            double[] iv = Bounds.interval(x, pool);
            double lo = iv[0];
            double hi = iv[1];
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || !Double.isFinite(c)) {
                return null;
            }
            if (hi <= c) {
                return x;
            }
            if (lo >= c) {
                return intern(new IRNode.Const(c));
            }
        } catch (RuntimeException ignore) {
            // Opaque extern / missing bounds — keep min.
        }
        return null;
    }

    private IRNode peepholeMaxWithOneConst(IRNode l, IRNode r) {
        if (l instanceof IRNode.Const cl && !(r instanceof IRNode.Const)) {
            return foldMaxConstAndExpr(cl.value(), r);
        }
        if (r instanceof IRNode.Const cr && !(l instanceof IRNode.Const)) {
            return foldMaxConstAndExpr(cr.value(), l);
        }
        return null;
    }

    private IRNode foldMaxConstAndExpr(double c, IRNode x) {
        try {
            double[] iv = Bounds.interval(x, pool);
            double lo = iv[0];
            double hi = iv[1];
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || !Double.isFinite(c)) {
                return null;
            }
            if (lo >= c) {
                return x;
            }
            if (hi <= c) {
                return intern(new IRNode.Const(c));
            }
        } catch (RuntimeException ignore) {
        }
        return null;
    }

    /* --------------------------------------------------------------------- */
    /* Cost model + helpers                                                  */
    /* --------------------------------------------------------------------- */

    private static boolean isConst(IRNode n, double v) {
        // Double.compare keeps -0.0 != 0.0 — important for ADD/SUB identities
        // because (-0.0) + x is NOT always == x in IEEE 754.
        return n instanceof IRNode.Const c && Double.compare(c.value(), v) == 0;
    }

    /**
     * "Cheap" = duplicating this node won't add real work in the generated
     * bytecode. Two cases qualify:
     * <ol>
     *   <li>Structural-leaf (Const, BlockX/Y/Z): single-instruction re-emit, never
     *       spilled by {@link RefCount}.</li>
     *   <li>Refcount &gt;= 2 in the current snapshot: already shared, so the
     *       codegen will spill it once and both uses become DLOAD-from-slot.
     *       Adding another reference is free.</li>
     * </ol>
     */
    private boolean cheap(IRNode n) {
        if (costOf(n) <= CHEAP_LEAF_COST) return true;
        Integer rc = refSnapshot.get(n);
        return rc != null && rc >= 2;
    }

    /**
     * Approximate inline-emit cost, in arbitrary "instruction" units. Only used to
     * gate strength reduction; a finer model (see {@code SizeEstimator}) is
     * overkill here. Anything that performs I/O or dispatches into opaque code
     * (noise, spline, marker, invoke, blend, range-choice) is treated as
     * MAX_VALUE so it's never duplicated.
     */
    private int costOf(IRNode n) {
        return switch (n) {
            case IRNode.Const c -> 1;
            case IRNode.BlockX bx -> 1;
            case IRNode.BlockY by -> 1;
            case IRNode.BlockZ bz -> 1;
            case IRNode.Bin bin -> sumCost(1, bin.left(), bin.right());
            case IRNode.Unary u -> addCost(1, u.input());
            case IRNode.Clamp cl -> addCost(2, cl.input());
            case IRNode.YClampedGradient g -> 3;
            case IRNode.Spline.Constant sc -> 1;
            // Anything else: noise samples, opaque externs, splines, blend
            // density, range choices — duplicating these is always a regression.
            default -> Integer.MAX_VALUE;
        };
    }

    private int sumCost(int self, IRNode a, IRNode b) {
        int ca = costOf(a);
        int cb = costOf(b);
        if (ca == Integer.MAX_VALUE || cb == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return self + ca + cb;
    }

    private int addCost(int self, IRNode a) {
        int ca = costOf(a);
        return ca == Integer.MAX_VALUE ? Integer.MAX_VALUE : self + ca;
    }
}
