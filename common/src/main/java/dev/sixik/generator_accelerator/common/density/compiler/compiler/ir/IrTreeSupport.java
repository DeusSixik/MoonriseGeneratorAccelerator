package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Graph walks over a hash-consed {@link IRNode} DAG (shared nodes, identity-not-equality).
 */
public final class IrTreeSupport {
    private IrTreeSupport() {}

    /** All structural children, including for node kinds {@link RefCount#children} omits. */
    public static List<IRNode> structuralChildren(IRNode n) {
        return switch (n) {
            case IRNode.Const c -> List.of();
            case IRNode.BlockX bx -> List.of();
            case IRNode.BlockY by -> List.of();
            case IRNode.BlockZ bz -> List.of();
            case IRNode.Bin bin -> List.of(bin.left(), bin.right());
            case IRNode.Unary u -> List.of(u.input());
            case IRNode.Clamp cl -> List.of(cl.input());
            case IRNode.RangeChoice rc -> List.of(rc.input(), rc.whenInRange(), rc.whenOutOfRange());
            case IRNode.YClampedGradient g -> List.of();
            case IRNode.Noise no -> List.of();
            case IRNode.ShiftedNoise sn -> List.of(sn.shiftX(), sn.shiftY(), sn.shiftZ());
            case IRNode.ShiftA sa -> List.of();
            case IRNode.ShiftB sb -> List.of();
            case IRNode.Shift s -> List.of();
            case IRNode.WeirdScaled w -> List.of(w.input());
            case IRNode.InlinedNoise in -> List.of(in.coordX(), in.coordY(), in.coordZ());
            case IRNode.InlinedBlendedNoise ibn -> List.of();
            case IRNode.WeirdRarity wr -> List.of(wr.input());
            case IRNode.EndIslands e -> List.of();
            case IRNode.Spline.Constant sc -> List.of();
            case IRNode.Spline.Multipoint mp -> {
                List<IRNode> ch = new ObjectArrayList<>(mp.values().size() + 1);
                ch.add(mp.coordinate());
                ch.addAll(mp.values());
                yield ch;
            }
            case IRNode.Marker m -> List.of();
            case IRNode.Invoke iv -> List.of();
            case IRNode.Beardifier b -> List.of();
            case IRNode.BlendDensity bd -> List.of(bd.input());
        };
    }

    /** Pre-order DFS, each distinct {@link IRNode} instance visited at most once. */
    public static void visitUnique(IRNode root, Consumer<IRNode> visit) {
        Map<IRNode, Boolean> seen = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            IRNode n = stack.pop();
            if (seen.put(n, Boolean.TRUE) != null) {
                continue;
            }
            visit.accept(n);
            for (IRNode c : structuralChildren(n)) {
                stack.push(c);
            }
        }
    }
}
