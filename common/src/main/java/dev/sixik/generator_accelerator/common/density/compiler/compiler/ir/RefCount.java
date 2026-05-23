package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts how many times each interned IR node is referenced from the root, then picks
 * the spill set: any node referenced &gt;= 2 times is materialised once into a local
 * slot via {@code DSTORE} and reloaded with {@code DLOAD} on subsequent uses; nodes
 * referenced exactly once are inlined and live only on the JVM operand stack.
 *
 * <p>Constants and pure context accessors ({@code BlockX/Y/Z}) are <em>never</em>
 * spilled: they're a single instruction, so spilling them costs more than it saves.
 */
public final class RefCount {

    public record Result(IdentityHashMap<IRNode, Integer> refs, List<IRNode> spillSet) {}

    /**
     * Reused across {@link IROptimizer} fixpoint iterations: {@link #compute(Workspace, IRNode)}
     * clears the internal ref map, avoiding a new {@link IdentityHashMap} on every
     * optimizer pass for large IR DAGs.
     */
    public static final class Workspace {
        public final IdentityHashMap<IRNode, Integer> refs = new IdentityHashMap<>();
    }

    private RefCount() {}

    public static Result compute(IRNode root) {
        return compute(new Workspace(), root);
    }

    /**
     * Fills {@code st.refs} in place, then returns a new {@link Result} whose
     * {@code refs()} view is the same map instance. Callers that retain the
     * {@code Result} must not reuse the same {@link Workspace} until the result is
     * discarded; {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IROptimizer} only holds one live result per iteration.
     */
    public static Result compute(Workspace st, IRNode root) {
        st.refs.clear();
        // BFS using identity to make sure interned-shared subtrees aren't double-counted.
        Deque<IRNode> stack = new ArrayDeque<>();
        Map<IRNode, Boolean> visited = new IdentityHashMap<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            st.refs.merge(n, 1, Integer::sum);
            if (visited.put(n, Boolean.TRUE) != null) continue;
            for (IRNode c : children(n)) stack.push(c);
        }

        List<IRNode> spillSet = new ArrayList<>();
        for (var e : st.refs.entrySet()) {
            IRNode n = e.getKey();
            if (e.getValue() < 2) continue;
            if (n instanceof IRNode.Const) continue;
            if (n instanceof IRNode.BlockX || n instanceof IRNode.BlockY || n instanceof IRNode.BlockZ) continue;
            spillSet.add(n);
        }
        return new Result(st.refs, spillSet);
    }

    public static Iterable<IRNode> children(IRNode n) {
        return switch (n) {
            case IRNode.Bin bin -> List.of(bin.left(), bin.right());
            case IRNode.Unary u -> List.of(u.input());
            case IRNode.Clamp c -> List.of(c.input());
            case IRNode.RangeChoice rc -> List.of(rc.input(), rc.whenInRange(), rc.whenOutOfRange());
            case IRNode.ShiftedNoise sn -> List.of(sn.shiftX(), sn.shiftY(), sn.shiftZ());
            case IRNode.WeirdScaled w -> List.of(w.input());
            case IRNode.InlinedNoise in -> List.of(in.coordX(), in.coordY(), in.coordZ());
            case IRNode.WeirdRarity wr -> List.of(wr.input());
            case IRNode.Spline.Multipoint mp -> {
                List<IRNode> all = new ArrayList<>(mp.values().size() + 1);
                all.add(mp.coordinate());
                all.addAll(mp.values());
                yield all;
            }
            case IRNode.BlendDensity bd -> List.of(bd.input());
            default -> List.of();
        };
    }
}
