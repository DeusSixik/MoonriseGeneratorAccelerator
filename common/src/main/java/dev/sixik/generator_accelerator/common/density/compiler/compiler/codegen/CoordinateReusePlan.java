package dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.RefCount;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

public final class CoordinateReusePlan {
    public static final CoordinateReusePlan EMPTY = new CoordinateReusePlan(Collections.emptySet());

    private final Set<IRNode> planned;

    private CoordinateReusePlan(Set<IRNode> planned) {
        this.planned = planned;
    }

    public static CoordinateReusePlan analyze(IRNode root, RefCount.Result refs) {
        if (root == null || refs == null) {
            return EMPTY;
        }

        IdentityHashMap<IRNode, Integer> coordUses = new IdentityHashMap<>();
        Deque<IRNode> stack = new ArrayDeque<>();
        Set<IRNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        stack.push(root);
        while (!stack.isEmpty()) {
            IRNode node = stack.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof IRNode.InlinedNoise noise) {
                countCoord(coordUses, noise.coordX());
                countCoord(coordUses, noise.coordY());
                countCoord(coordUses, noise.coordZ());
            }
            for (IRNode child : RefCount.children(node)) {
                stack.push(child);
            }
        }

        Set<IRNode> planned = Collections.newSetFromMap(new IdentityHashMap<>());
        IdentityHashMap<IRNode, Boolean> unsafeCache = new IdentityHashMap<>();
        for (IRNode coord : coordUses.keySet()) {
            int coordUseCount = coordUses.getOrDefault(coord, 0);
            int refCount = refs.refs().getOrDefault(coord, 0);
            if ((coordUseCount >= 2 || refCount >= 2) && canReuse(coord, unsafeCache)) {
                planned.add(coord);
            }
        }

        return planned.isEmpty() ? EMPTY : new CoordinateReusePlan(planned);
    }

    public boolean contains(IRNode node) {
        return planned.contains(node);
    }

    private static void countCoord(IdentityHashMap<IRNode, Integer> coordUses, IRNode coord) {
        coordUses.merge(coord, 1, Integer::sum);
    }

    private static boolean canReuse(IRNode node, IdentityHashMap<IRNode, Boolean> unsafeCache) {
        return !isTrivial(node) && !containsUnsafe(node, unsafeCache);
    }

    private static boolean isTrivial(IRNode node) {
        return node instanceof IRNode.Const
                || node instanceof IRNode.BlockX
                || node instanceof IRNode.BlockY
                || node instanceof IRNode.BlockZ;
    }

    private static boolean containsUnsafe(IRNode node, IdentityHashMap<IRNode, Boolean> unsafeCache) {
        Boolean cached = unsafeCache.get(node);
        if (cached != null) {
            return cached;
        }

        Deque<ScanFrame> stack = new ArrayDeque<>();
        stack.push(new ScanFrame(node, false));
        while (!stack.isEmpty()) {
            ScanFrame frame = stack.pop();
            IRNode current = frame.node();
            if (unsafeCache.containsKey(current)) {
                continue;
            }
            if (isUnsafeLeaf(current)) {
                unsafeCache.put(current, Boolean.TRUE);
                continue;
            }
            if (frame.expanded()) {
                boolean unsafe = false;
                for (IRNode child : RefCount.children(current)) {
                    if (Boolean.TRUE.equals(unsafeCache.get(child))) {
                        unsafe = true;
                        break;
                    }
                }
                unsafeCache.put(current, unsafe);
                continue;
            }

            stack.push(new ScanFrame(current, true));
            for (IRNode child : RefCount.children(current)) {
                if (!unsafeCache.containsKey(child)) {
                    stack.push(new ScanFrame(child, false));
                }
            }
        }
        return Boolean.TRUE.equals(unsafeCache.get(node));
    }

    private static boolean isUnsafeLeaf(IRNode node) {
        return node instanceof IRNode.Invoke
                || node instanceof IRNode.Marker
                || node instanceof IRNode.Beardifier
                || node instanceof IRNode.EndIslands
                || node instanceof IRNode.BlendDensity;
    }

    private record ScanFrame(IRNode node, boolean expanded) {}
}
