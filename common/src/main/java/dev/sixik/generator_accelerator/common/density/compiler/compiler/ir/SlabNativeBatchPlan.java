package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcNativePlanningStats;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Compile-time plan for native slab batching inside {@code fillArray} when a cell-lattice
 * {@code lattice_inner} runs once per (x,z) cell. Skips nodes only evaluated in
 * {@code lattice_y} / {@code lattice_xz} (inside the hoisted subtree).
 *
 * <p>Supports {@link CellLatticeOption.Axis#Y_ONLY} (xz slab per Y slice) and
 * {@link CellLatticeOption.Axis#XZ_ONLY} (Y column at fixed in-cell XZ).
 */
public final class SlabNativeBatchPlan {

    public sealed interface Slot permits NormalSlot, BlendedSlot, MarkerSlot {
        /** Index into {@code nativeSlabOut[slotIndex]} in {@code lattice_inner_batched}. */
        int slotIndex();

        /** Index into {@code nativeNoiseHandles} for {@link DfcNativeBridge} batch calls. */
        int nativeHandleIndex(int noiseSpecCount);
    }

    public record NormalSlot(int slotIndex, IRNode.InlinedNoise noise) implements Slot {
        @Override
        public int nativeHandleIndex(int noiseSpecCount) {
            return noise.specPoolIndex();
        }
    }

    public record BlendedSlot(int slotIndex, IRNode.InlinedBlendedNoise noise) implements Slot {
        @Override
        public int nativeHandleIndex(int noiseSpecCount) {
            return noiseSpecCount + noise.blendedSpecIndex();
        }
    }

    public record MarkerSlot(int slotIndex, IRNode.Marker marker) implements Slot {
        @Override
        public int nativeHandleIndex(int noiseSpecCount) {
            return -1;
        }
    }

    private final List<Slot> slots;

    private SlabNativeBatchPlan(List<Slot> slots) {
        this.slots = List.copyOf(slots);
    }

    public List<Slot> slots() {
        return slots;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * @param root                 density IR root (same as lattice analysis)
     * @param plan                 non-null lattice plan; must be {@link CellLatticeOption.Axis#Y_ONLY}
     * @param noiseSpecCount       normal noise spec count (handle indices {@code 0 .. count-1})
     * @param blendedNoiseSpecCount blended spec count (handle indices {@code noiseSpecCount + j})
     */
    public static Optional<SlabNativeBatchPlan> analyze(IRNode root, CellLatticeOption.LatticePlan plan,
                                                        int noiseSpecCount, int blendedNoiseSpecCount) {
        if (plan.hoistAxis() != CellLatticeOption.Axis.Y_ONLY
                && plan.hoistAxis() != CellLatticeOption.Axis.XZ_ONLY) {
            return Optional.empty();
        }
        IRNode hoisted = plan.hoistedSubtree();
        List<Slot> out = new ObjectArrayList<>();
        IdentityHashMap<IRNode, Boolean> assigned = new IdentityHashMap<>();
        int[] nextIdx = {0};

        collectSlots(root, hoisted, out, assigned, nextIdx);
        if (out.isEmpty()) {
            DfcNativePlanningStats.recordSlabPlanMissingNoSlots();
            return Optional.empty();
        }
        for (Slot s : out) {
            if (s instanceof NormalSlot ns) {
                if (!coordExprSlabSafe(ns.noise())) {
                    DfcNativePlanningStats.recordSlabPlanMissingUnsafeCoords();
                    return Optional.empty();
                }
                if (ns.noise().specPoolIndex() < 0 || ns.noise().specPoolIndex() >= noiseSpecCount) {
                    DfcNativePlanningStats.recordSlabPlanMissingBadHandleIndex();
                    return Optional.empty();
                }
            } else if (s instanceof BlendedSlot bs) {
                int j = bs.noise().blendedSpecIndex();
                if (j < 0 || j >= blendedNoiseSpecCount) {
                    DfcNativePlanningStats.recordSlabPlanMissingBadHandleIndex();
                    return Optional.empty();
                }
            } else if (!(s instanceof MarkerSlot)) {
                DfcNativePlanningStats.recordSlabPlanMissingBadHandleIndex();
                return Optional.empty();
            }
        }
        return Optional.of(new SlabNativeBatchPlan(out));
    }

    /**
     * DFS from {@code n}. When {@code n == hoisted}, stop descending — {@code lattice_inner}
     * treats the hoisted node as a single spilled value and never visits its children.
     */
    private static void collectSlots(IRNode n, IRNode hoisted, List<Slot> out,
                                     IdentityHashMap<IRNode, Boolean> assigned, int[] nextIdx) {
        if (n == hoisted) {
            return;
        }
        if (n instanceof IRNode.InlinedNoise in) {
            if (assigned.put(in, Boolean.TRUE) == null) {
                out.add(new NormalSlot(nextIdx[0]++, in));
            }
        } else if (n instanceof IRNode.InlinedBlendedNoise ib) {
            if (assigned.put(ib, Boolean.TRUE) == null) {
                out.add(new BlendedSlot(nextIdx[0]++, ib));
            }
        } else if (n instanceof IRNode.Marker marker) {
            if (assigned.put(marker, Boolean.TRUE) == null) {
                out.add(new MarkerSlot(nextIdx[0]++, marker));
            }
        }
        for (IRNode c : RefCount.children(n)) {
            collectSlots(c, hoisted, out, assigned, nextIdx);
        }
    }

    static boolean coordExprSlabSafe(IRNode.InlinedNoise in) {
        return slabSafe(in.coordX()) && slabSafe(in.coordY()) && slabSafe(in.coordZ());
    }

    private static boolean slabSafe(IRNode n) {
        return switch (n) {
            case IRNode.Const ignoredC -> true;
            case IRNode.BlockX ignoredX -> true;
            case IRNode.BlockY ignoredY -> true;
            case IRNode.BlockZ ignoredZ -> true;
            case IRNode.YClampedGradient ignoredG -> true;
            case IRNode.Bin b -> slabSafe(b.left()) && slabSafe(b.right());
            case IRNode.Unary u -> slabSafe(u.input());
            case IRNode.Clamp c -> slabSafe(c.input());
            case IRNode.RangeChoice rc -> slabSafe(rc.input()) && slabSafe(rc.whenInRange()) && slabSafe(rc.whenOutOfRange());
            case IRNode.Spline.Constant ignoredSplineC -> true;
            case IRNode.Spline.Multipoint mp ->
                    slabSafe(mp.coordinate()) && mp.values().stream().allMatch(SlabNativeBatchPlan::slabSafeSpline);
            case IRNode.InlinedNoise in ->
                    slabSafe(in.coordX()) && slabSafe(in.coordY()) && slabSafe(in.coordZ());
            case IRNode.InlinedBlendedNoise ignoredBlended -> true;
            case IRNode.WeirdRarity wr -> slabSafe(wr.input());
            default -> false;
        };
    }

    private static boolean slabSafeSpline(IRNode.Spline s) {
        return switch (s) {
            case IRNode.Spline.Constant ignoredSc -> true;
            case IRNode.Spline.Multipoint mp ->
                    slabSafe(mp.coordinate()) && mp.values().stream().allMatch(SlabNativeBatchPlan::slabSafeSpline);
        };
    }
}
