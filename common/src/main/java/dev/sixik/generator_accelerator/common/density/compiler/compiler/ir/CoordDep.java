package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import net.sixik.javastructg.structs.maps.Object2NativeMap;
import net.sixik.javastructg.utils.NativeUtils;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies IR subtrees as depending on one or more of {@code blockX} / {@code blockY} /
 * {@code blockZ} (or none), for Tier-4 batch / hoisting work.
 */
public final class CoordDep {

    public static final class Flags {
        public static final Flags NONE = new Flags(false, false, false);

        private boolean usesX;
        private boolean usesY;
        private boolean usesZ;

        public Flags() {
            this(false, false, false);
        }

        public Flags(boolean usesX, boolean usesY, boolean usesZ) {
            this.usesX = usesX;
            this.usesY = usesY;
            this.usesZ = usesZ;
        }

        public void setUsesX(boolean usesX) {
            this.usesX = usesX;
        }

        public void setUsesY(boolean usesY) {
            this.usesY = usesY;
        }

        public void setUsesZ(boolean usesZ) {
            this.usesZ = usesZ;
        }

        public boolean usesX() {
            return usesX;
        }

        public boolean usesY() {
            return usesY;
        }

        public boolean usesZ() {
            return usesZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (Flags) obj;
            return this.usesX == that.usesX &&
                    this.usesY == that.usesY &&
                    this.usesZ == that.usesZ;
        }

        @Override
        public int hashCode() {
            return NativeUtils.mix(
                    (usesX ? 1 : 0) |
                            ((usesY ? 1 : 0) << 1) |
                            ((usesZ ? 1 : 0) << 2)
            );
        }

        @Override
        public String toString() {
            return "Flags[" +
                    "usesX=" + usesX + ", " +
                    "usesY=" + usesY + ", " +
                    "usesZ=" + usesZ + ']';
        }
    }

    private CoordDep() {}

    /**
     * For each node in the DAG, post-order: whether evaluating that node
     * reads any of blockX / Y / Z (directly or through descendants).
     */
    public static Map<IRNode, Flags> flagsForAllNodes(IRNode root) {
        Map<IRNode, Flags> out = new IdentityHashMap<>();
        postOrder(root, out);
        return out;
    }

    private static void postOrder(IRNode n, Map<IRNode, Flags> out) {
        if (n == null || out.containsKey(n)) {
            return;
        }
        for (IRNode c : RefCount.children(n)) {
            postOrder(c, out);
        }
        out.put(n, computeFlags(n, out));
    }

    public static boolean usesAnyBlockCoord(IRNode root) {
        Flags f = flagsForAllNodes(root).get(root);
        return f != null && (f.usesX() || f.usesY() || f.usesZ());
    }

    public static Set<IRNode> nodesWithNoBlockCoords(Map<IRNode, Flags> map) {
        return map.entrySet().stream()
                .filter(e -> e.getValue().equals(Flags.NONE))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Flags merge(Flags a, Flags b) {
        return new Flags(a.usesX() || b.usesX(), a.usesY() || b.usesY(), a.usesZ() || b.usesZ());
    }

    private static Flags ofChildren(IRNode n, Map<IRNode, Flags> done) {
        Flags acc = Flags.NONE;
        for (IRNode c : RefCount.children(n)) {
            Flags f = done.get(c);
            if (f != null) {
                acc = merge(acc, f);
            }
        }
        return acc;
    }

    private static final Flags ALL = new Flags(true, true, true);

    private static Flags computeFlags(IRNode n, Map<IRNode, Flags> done) {
        if (n instanceof IRNode.BlockX) {
            return new Flags(true, false, false);
        }
        if (n instanceof IRNode.BlockY) {
            return new Flags(false, true, false);
        }
        if (n instanceof IRNode.BlockZ) {
            return new Flags(false, false, true);
        }
        if (n instanceof IRNode.Const || n instanceof IRNode.Spline.Constant) {
            return Flags.NONE;
        }
        // YClampedGradient samples blockY directly via the FunctionContext.
        if (n instanceof IRNode.YClampedGradient) {
            return new Flags(false, true, false);
        }
        // Legacy noise IR nodes that read context coordinates internally before
        // NoiseExpander rewrites them. Classify them as coord-dependent on the
        // axes they actually sample, not as Flags.NONE (which would let the
        // lattice planner mistakenly hoist noise sampling out of an X/Y/Z loop).
        // - Noise samples (x, y, z)            -> all three
        // - Shift / ShiftA / ShiftB samples    -> all three (they read raw coords)
        // - WeirdScaled defers to its input plus an internal noise sample (XZ)
        if (n instanceof IRNode.Noise) {
            return ALL;
        }
        if (n instanceof IRNode.Shift || n instanceof IRNode.ShiftA || n instanceof IRNode.ShiftB) {
            return ALL;
        }
        if (n instanceof IRNode.InlinedBlendedNoise) {
            // BlendedNoise.compute reads x, y, z from the context — not a hoist candidate.
            return ALL;
        }
        // Marker / Invoke / EndIslands are extern call sites whose body we can't
        // see. We have to assume they read every coordinate; otherwise we might
        // hoist a Y-only lattice subtree above a marker that, post-NoiseChunk
        // wrap, depends on (x, y, z) cell-cache state.
        if (n instanceof IRNode.Marker || n instanceof IRNode.Invoke
                || n instanceof IRNode.EndIslands || n instanceof IRNode.Beardifier) {
            return ALL;
        }
        return ofChildren(n, done);
    }
}
