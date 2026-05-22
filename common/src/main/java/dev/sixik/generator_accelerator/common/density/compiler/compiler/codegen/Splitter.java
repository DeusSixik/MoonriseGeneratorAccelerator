package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decides which IR nodes the codegen should hoist into their own
 * {@code helper$N(self, ctx)} static methods, so that no method's body
 * exceeds the JVM 64 KiB code-attribute limit (we use ~50 KiB as a budget
 * to leave headroom for ASM frame insertion and worst-case opcode-size
 * estimates).
 *
 * <p>Policy:
 * <ol>
 *   <li>Every {@link IRNode.Spline.Multipoint} is extracted unconditionally —
 *       the inlined cubic-interp ladder is the dominant source of overflow,
 *       and even small splines benefit from being broken out so HotSpot can
 *       reuse the same helper across call sites.</li>
 *   <li>Any IR node referenced &gt;= 2 times (CSE-shared) whose inlined
 *       bytecode size exceeds 8 KiB is also extracted, to amortise the body
 *       across all references and to free the parent method.</li>
 *   <li>Greedy budget pass: while any method's estimated body size exceeds
 *       {@link #METHOD_BUDGET_BYTES}, pick the largest extractable descendant
 *       of that method and extract it, repeat. Throws
 *       {@link BytecodeTooLargeException} if no further extraction would
 *       help (degenerate case — only realistic for pathological
 *       Spline.Multipoint with thousands of points).</li>
 * </ol>
 */
public final class Splitter {

    /** Maximum estimated body size per generated method, leaving headroom under the 64 KiB JVM limit. */
    public static final int METHOD_BUDGET_BYTES = 50_000;
    /** Threshold for forcing extraction of CSE-shared subtrees. */
    public static final int BIG_SHARED_THRESHOLD_BYTES = 8_000;
    /** Don't bother extracting nodes whose own body is barely larger than a call site. */
    public static final int MIN_EXTRACT_GAIN_BYTES = 32;
    /** Hard ceiling on greedy extractions per call to avoid pathological loops. */
    public static final int GREEDY_LIMIT = 100_000;

    private Splitter() {}

    /**
     * Compute the extracted-node set for a single compiled DF.
     *
     * <p>The returned set is identity-keyed; callers should not mutate it.
     * {@link Codegen} consults this set on every {@code emit()} call: a node
     * present in the set is rendered as an {@code INVOKESTATIC} of a freshly
     * generated helper instead of being inlined.
     */
    public static Set<IRNode> plan(IRNode root, RefCount.Result rc) {
        return plan(root, rc, null);
    }

    /** Plan extraction with a {@link ConstantPool} so {@link SizeEstimator} can size InlinedNoise nodes accurately. */
    public static Set<IRNode> plan(IRNode root, RefCount.Result rc, ConstantPool pool) {
        Set<IRNode> extracted = Collections.newSetFromMap(new IdentityHashMap<>());
        SizeEstimator est = new SizeEstimator(pool);

        // Phase 1: every Spline.Multipoint is its own helper.
        walkUnique(root, n -> {
            if (n instanceof IRNode.Spline.Multipoint) extracted.add(n);
        });
        // No invalidate here: SizeEstimator is unused until phase 2 (no sizing in phase 1).

        // Phase 2: CSE-shared nodes with big inline footprint -> helpers.
        walkUnique(root, n -> {
            if (extracted.contains(n)) return;
            if (!isExtractable(n)) return;
            Integer r = rc.refs().get(n);
            if (r == null || r < 2) return;
            int sz = est.size(n, extracted);
            if (sz > BIG_SHARED_THRESHOLD_BYTES) extracted.add(n);
        });
        est.invalidate();

        // Phase 3: greedy budget enforcement.
        for (int iter = 0; iter < GREEDY_LIMIT; iter++) {
            IRNode oversize = findOversizeMethodRoot(root, extracted, est);
            if (oversize == null) return extracted;
            IRNode pick = pickLargestExtractableDescendant(oversize, extracted, est);
            if (pick == null) {
                throw new BytecodeTooLargeException(
                        "Cannot split DF further: method root " + oversize.getClass().getSimpleName()
                                + " body still " + est.size(oversize, extracted)
                                + " bytes after extracting "
                                + extracted.size() + " helpers");
            }
            extracted.add(pick);
            est.invalidate();
        }
        throw new BytecodeTooLargeException("Splitter greedy loop exceeded " + GREEDY_LIMIT + " iterations");
    }

    /** Walk every distinct (identity) node reachable from {@code root}. */
    private static void walkUnique(IRNode root, Consumer<IRNode> visit) {
        Map<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) continue;
            visit.accept(n);
            for (IRNode c : RefCount.children(n)) stack.push(c);
        }
    }

    /** Return any method-root (the main root or any extracted node) whose estimated size > budget. */
    private static IRNode findOversizeMethodRoot(IRNode root, Set<IRNode> extracted, SizeEstimator est) {
        if (est.size(root, extracted) > METHOD_BUDGET_BYTES) return root;
        IRNode worst = null;
        int worstSize = METHOD_BUDGET_BYTES;
        for (IRNode m : extracted) {
            int s = est.size(m, extracted);
            if (s > worstSize) {
                worstSize = s;
                worst = m;
            }
        }
        return worst;
    }

    /**
     * Inside the body of method-root {@code methodRoot}, find the largest
     * descendant that's not already extracted and whose own helper body
     * would itself fit one method. Returns {@code null} if no such
     * descendant exists (which means the splitter has done all it can).
     */
    private static IRNode pickLargestExtractableDescendant(IRNode methodRoot, Set<IRNode> extracted,
                                                           SizeEstimator est) {
        // Walk descendants of methodRoot, but stop descending into already-extracted children
        // (they're separate helpers with their own splitting; we only care about this method's body).
        Map<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        for (IRNode c : RefCount.children(methodRoot)) stack.push(c);
        IRNode best = null;
        int bestSize = MIN_EXTRACT_GAIN_BYTES;
        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) continue;
            if (extracted.contains(n)) {
                // Already its own helper — don't recurse into it. Its body is independently sized.
                continue;
            }
            if (isExtractable(n)) {
                int s = est.size(n, extracted);
                // Helper body must fit one method.
                if (s <= METHOD_BUDGET_BYTES && s > bestSize) {
                    bestSize = s;
                    best = n;
                }
            }
            for (IRNode c : RefCount.children(n)) stack.push(c);
        }
        return best;
    }

    /** Whether a node is sensible to extract: emitting a 7-byte call site replaces something larger. */
    private static boolean isExtractable(IRNode n) {
        if (n instanceof IRNode.Const) return false;
        if (n instanceof IRNode.BlockX || n instanceof IRNode.BlockY || n instanceof IRNode.BlockZ) return false;
        if (n instanceof IRNode.Spline.Constant) return false;
        return true;
    }

    /** Convenience for tests / logging. */
    public static List<IRNode> orderedExtracted(Set<IRNode> extracted, IRNode root) {
        List<IRNode> ordered = new ObjectArrayList<>(extracted.size());
        Map<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) continue;
            if (extracted.contains(n)) ordered.add(n);
            for (IRNode c : RefCount.children(n)) stack.push(c);
        }
        return ordered;
    }
}
