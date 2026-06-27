package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * "Cell-lattice" hoisting analysis. Identifies the largest sub-expression of a
 * compiled density function whose value depends on <em>only one</em> of the
 * coordinate axes (Y-only or XZ-only) so the
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen}
 * can emit a {@code fillArray} fast path that evaluates the sub-expression once
 * per axis-position and reuses the result across the orthogonal slab.
 *
 * <h2>Why this matters</h2>
 *
 * <p>{@code NoiseChunk.fillArray} typically iterates a triple loop
 * {@code y -> x -> z} over a 4×4×4 (or similar) cell. A subtree that depends
 * only on {@code blockY} gets recomputed {@code cellWidth*cellWidth} times per
 * Y position even though its value never changes inside that slab. For a typical
 * vanilla terrain router the {@code YClampedGradient} chain that drives
 * "slope from sea level to bedrock" is a textbook Y-only hoist: ~5 IR nodes,
 * {@code 16x} per-cell redundancy.
 *
 * <p>Symmetrically, an XZ-only subtree (e.g. a 2D continentalness blend that
 * does not vary with {@code blockY}) gets recomputed {@code cellHeight} times
 * per (x, z) column.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Compute {@link CoordDep.Flags} for every node post-order.</li>
 *   <li>Compute subtree size (number of distinct nodes reachable from the
 *       node, dedupped via identity).</li>
 *   <li>Walk the tree top-down looking for the largest Y-only subtree
 *       (and only descend into a node's children when the node itself is
 *       <em>not</em> Y-only). The first Y-only node we hit is automatically
 *       the maximal Y-only ancestor on its branch.</li>
 *   <li>If no Y-only candidate of meaningful size is found, repeat for
 *       XZ-only.</li>
 *   <li>Reject if the candidate is the {@code root} itself (degenerate;
 *       the whole DF would just be a constant per Y, which a smarter
 *       caching layer would already collapse).</li>
 *   <li>Reject if the candidate's size is below {@link #MIN_HOIST_SIZE} —
 *       below that, the helper-call overhead outweighs the savings.</li>
 * </ol>
 *
 * <h2>Marker boundaries</h2>
 *
 * <p>{@link IRNode.Marker}, {@link IRNode.Invoke}, and {@link IRNode.EndIslands}
 * are extern call sites whose body we cannot see. {@link CoordDep} classifies
 * them as depending on every axis precisely so the planner cannot accidentally
 * lift a marker out of an X/Y/Z loop and end up sampling a stale cell-cache
 * value.
 */
public final class CellLatticeOption {

    /**
     * Minimum number of distinct IR nodes a hoist candidate must contain
     * before we consider it a worthwhile lattice hoist. Below this, the
     * fixed overhead of the helper call-and-load sequence dominates the
     * savings — the JIT will inline a 2-3 node arithmetic chain at every
     * call site anyway. Tuned against the {@code YClampedGradient} chain
     * which sits at exactly 5 IR nodes (gradient, mul, add, clamp, etc.).
     */
    public static final int MIN_HOIST_SIZE = 5;

    /** Which axis-slab the hoisted sub-expression varies along. */
    public enum Axis {
        /** Subtree depends only on {@code blockY}; eval once per Y slab. */
        Y_ONLY,
        /** Subtree depends only on {@code blockX} and/or {@code blockZ}; eval once per (x, z) column. */
        XZ_ONLY,
        /** Sentinel for "no plan" — never produced by {@link #analyze}, used in
         *  {@code /dfc stats} when the field is absent. */
        NONE
    }

    /**
     * One hoistable sub-expression and the axis it varies along.
     *
     * @param root              the original DF root that was analyzed
     * @param hoistAxis         which axis the {@code hoistedSubtree} varies along
     * @param hoistedSubtree    the largest qualifying axis-only subtree under {@code root}
     * @param hoistedNodeCount  number of distinct IR nodes in the hoisted subtree
     *                          (used by the codegen to size scratch arrays and by
     *                          {@code /dfc stats} to surface the planner's output)
     */
    public record LatticePlan(
            IRNode root,
            Axis hoistAxis,
            IRNode hoistedSubtree,
            int hoistedNodeCount) {
    }

    private CellLatticeOption() {}

    /**
     * Run the analysis over {@code root}. Returns an empty {@link Optional}
     * when no qualifying hoist exists (constant tree, root itself is
     * axis-only, or all candidates are below {@link #MIN_HOIST_SIZE}).
     *
     * <p>Pre-computed {@code dep} is accepted so callers that already paid
     * the {@link CoordDep#flagsForAllNodes} cost (e.g. for an unrelated
     * pass) can reuse it.
     */
    public static Optional<LatticePlan> analyze(IRNode root, Map<IRNode, CoordDep.Flags> dep) {
        if (root == null) return Optional.empty();
        IdentityHashMap<IRNode, Integer> sizes = new IdentityHashMap<>();
        subtreeSize(root, sizes);

        Optional<LatticePlan> y = findLargestAxisOnly(root, dep, sizes, /* yOnly */ true);
        if (y.isPresent()) return y;
        return findLargestAxisOnly(root, dep, sizes, /* yOnly */ false);
    }

    /** Convenience that computes {@code dep} itself. */
    public static Optional<LatticePlan> analyze(IRNode root) {
        if (root == null) return Optional.empty();
        return analyze(root, CoordDep.flagsForAllNodes(root));
    }

    private static int subtreeSize(IRNode n, IdentityHashMap<IRNode, Integer> out) {
        if (n == null) return 0;
        Integer cached = out.get(n);
        if (cached != null) return cached;
        // Reserve the slot eagerly to make recursive cycles benign even though
        // the IR is structurally a DAG (no cycles). The post-set below corrects
        // the count once children resolve.
        out.put(n, 0);
        int total = 1;
        for (IRNode c : RefCount.children(n)) {
            total += subtreeSize(c, out);
        }
        out.put(n, total);
        return total;
    }

    /**
     * Walk top-down from {@code root}; the first node whose flags satisfy the
     * axis-only predicate is, by definition, the maximal axis-only subtree on
     * that branch (any ancestor either also matches and would have been picked
     * earlier in our top-down walk, or is not axis-only). Pick the largest
     * candidate across all branches.
     */
    private static Optional<LatticePlan> findLargestAxisOnly(
            IRNode root,
            Map<IRNode, CoordDep.Flags> dep,
            IdentityHashMap<IRNode, Integer> sizes,
            boolean yOnly) {

        IRNode best = null;
        int bestSize = 0;

        IdentityHashMap<IRNode, Boolean> visited = new IdentityHashMap<>();
        java.util.ArrayDeque<IRNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);

        CoordDep.Flags mutableFlag = new CoordDep.Flags();

        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            if (visited.put(n, Boolean.TRUE) != null) continue;
            CoordDep.Flags f = dep.get(n);
            if (f != null && matchesAxisOnly(f, yOnly)) {
                // Reject the root itself — the whole DF being axis-only means
                // the lattice would replace the entire compute with a single
                // table lookup, which is strictly the job of an outer cache
                // marker (CacheOnce / FlatCache) the user already wrote.
                if (n != root) {
                    int sz = sizes.getOrDefault(n, 0);
                    if (sz >= MIN_HOIST_SIZE && sz > bestSize) {
                        best = n;
                        bestSize = sz;
                    }
                }
                // Don't descend into an axis-only subtree: any inner node
                // is necessarily also axis-only (axis flags are monotone
                // up children), but those inner nodes would be smaller —
                // we already have the maximal candidate on this branch.
                continue;
            }
            // Not axis-only here: descend.
            for (IRNode c : RefCount.children(n)) {
                stack.push(c);
            }
        }

        if (best == null) return Optional.empty();
        Axis ax = yOnly ? Axis.Y_ONLY : Axis.XZ_ONLY;
        return Optional.of(new LatticePlan(root, ax, best, bestSize));
    }

    /**
     * For Y-only: {@code usesY} but not {@code usesX} or {@code usesZ}. We also
     * require <em>some</em> coord dependency — a constant subtree is not
     * useful to hoist (it'd be folded by the optimizer and the codegen would
     * emit it as a single LDC).
     *
     * <p>For XZ-only: {@code !usesY} and at least one of {@code usesX} /
     * {@code usesZ}. We deliberately accept "X-only" and "Z-only" as XZ-slab
     * candidates — the slab iteration covers both axes, so the precompute is
     * still amortised across {@code cellWidth} (or {@code cellWidth^2}) inner-loop
     * iterations.
     */
    private static boolean matchesAxisOnly(CoordDep.Flags f, boolean yOnly) {
        if (yOnly) {
            return f.usesY() && !f.usesX() && !f.usesZ();
        }
        return !f.usesY() && (f.usesX() || f.usesZ());
    }
}
