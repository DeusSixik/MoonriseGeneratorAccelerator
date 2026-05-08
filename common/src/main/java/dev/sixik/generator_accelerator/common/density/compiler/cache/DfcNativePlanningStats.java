package dev.sixik.generator_accelerator.common.density.compiler.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Compile-time counters for native slab planning decisions.
 */
public final class DfcNativePlanningStats {
    private static final LongAdder LATTICE_ROOTS = new LongAdder();
    private static final LongAdder NATIVE_OPS_DISABLED = new LongAdder();
    private static final LongAdder SLAB_PLAN_PRESENT = new LongAdder();
    private static final LongAdder SLAB_PLAN_MISSING = new LongAdder();
    private static final LongAdder SLAB_PLAN_MISSING_NO_SLOTS = new LongAdder();
    private static final LongAdder SLAB_PLAN_MISSING_UNSAFE_COORDS = new LongAdder();
    private static final LongAdder SLAB_PLAN_MISSING_BAD_HANDLE_INDEX = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_PRESENT = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_MISSING = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_MISSING_EXTRACTED = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_MISSING_UNSUPPORTED_NODE = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_MISSING_INVALID_PROGRAM = new LongAdder();
    private static final LongAdder SLAB_INNER_VM_MISSING_IO = new LongAdder();
    private static final LongAdder AXIS_Y_ONLY = new LongAdder();
    private static final LongAdder AXIS_XZ_ONLY = new LongAdder();
    private static final ConcurrentHashMap<String, Boolean> SLAB_INNER_UNSUPPORTED_CLASSES = new ConcurrentHashMap<>();

    private DfcNativePlanningStats() {
    }

    public record Stats(long latticeRoots, long nativeOpsDisabled, long slabPlanPresent,
                        long slabPlanMissing, long slabPlanMissingNoSlots, long slabPlanMissingUnsafeCoords,
                        long slabPlanMissingBadHandleIndex, long slabInnerVmPresent, long slabInnerVmMissing,
                        long slabInnerVmMissingExtracted, long slabInnerVmMissingUnsupportedNode,
                        long slabInnerVmMissingInvalidProgram, long slabInnerVmMissingIo,
                        List<String> slabInnerUnsupportedClasses, long axisYOnly, long axisXzOnly) {
    }

    public static Stats snapshot() {
        return new Stats(LATTICE_ROOTS.sum(), NATIVE_OPS_DISABLED.sum(), SLAB_PLAN_PRESENT.sum(),
                SLAB_PLAN_MISSING.sum(), SLAB_PLAN_MISSING_NO_SLOTS.sum(),
                SLAB_PLAN_MISSING_UNSAFE_COORDS.sum(), SLAB_PLAN_MISSING_BAD_HANDLE_INDEX.sum(),
                SLAB_INNER_VM_PRESENT.sum(), SLAB_INNER_VM_MISSING.sum(),
                SLAB_INNER_VM_MISSING_EXTRACTED.sum(), SLAB_INNER_VM_MISSING_UNSUPPORTED_NODE.sum(),
                SLAB_INNER_VM_MISSING_INVALID_PROGRAM.sum(), SLAB_INNER_VM_MISSING_IO.sum(),
                new ArrayList<>(SLAB_INNER_UNSUPPORTED_CLASSES.keySet()),
                AXIS_Y_ONLY.sum(), AXIS_XZ_ONLY.sum());
    }

    public static void recordLatticeRoot(boolean nativeOpsEnabled, boolean xzOnly) {
        LATTICE_ROOTS.increment();
        if (!nativeOpsEnabled) {
            NATIVE_OPS_DISABLED.increment();
        }
        if (xzOnly) {
            AXIS_XZ_ONLY.increment();
        } else {
            AXIS_Y_ONLY.increment();
        }
    }

    public static void recordSlabPlan(boolean present) {
        if (present) {
            SLAB_PLAN_PRESENT.increment();
        } else {
            SLAB_PLAN_MISSING.increment();
        }
    }

    public static void recordSlabPlanMissingNoSlots() {
        SLAB_PLAN_MISSING_NO_SLOTS.increment();
    }

    public static void recordSlabPlanMissingUnsafeCoords() {
        SLAB_PLAN_MISSING_UNSAFE_COORDS.increment();
    }

    public static void recordSlabPlanMissingBadHandleIndex() {
        SLAB_PLAN_MISSING_BAD_HANDLE_INDEX.increment();
    }

    public static void recordSlabInnerVm(boolean present) {
        if (present) {
            SLAB_INNER_VM_PRESENT.increment();
        } else {
            SLAB_INNER_VM_MISSING.increment();
        }
    }

    public static void recordSlabInnerMissingExtracted() {
        SLAB_INNER_VM_MISSING_EXTRACTED.increment();
    }

    public static void recordSlabInnerMissingUnsupportedNode(String className) {
        SLAB_INNER_VM_MISSING_UNSUPPORTED_NODE.increment();
        if (SLAB_INNER_UNSUPPORTED_CLASSES.size() < 8) {
            SLAB_INNER_UNSUPPORTED_CLASSES.putIfAbsent(className, Boolean.TRUE);
        }
    }

    public static void recordSlabInnerMissingInvalidProgram() {
        SLAB_INNER_VM_MISSING_INVALID_PROGRAM.increment();
    }

    public static void recordSlabInnerMissingIo() {
        SLAB_INNER_VM_MISSING_IO.increment();
    }
}
