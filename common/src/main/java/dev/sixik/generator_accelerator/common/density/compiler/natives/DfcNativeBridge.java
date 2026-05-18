package dev.sixik.generator_accelerator.common.density.compiler.natives;

import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClRuntime;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClStats;

/**
 * JNI entry points for batched / SIMD-capable noise kernels. Thread-safe: native descriptors
 * are immutable after allocation; batch methods only read them.
 *
 * <p>Lifecycle: {@link #allocNormalNoiseStack} / {@link #allocBlendedSpec} return an opaque
 * handle (non-zero). Call the matching {@code release*} from the owning registry when the
 * descriptor is no longer needed (e.g. datapack reload). Never mix release types on a handle.
 */
public final class DfcNativeBridge {

    private static final boolean LIB_OK;
    private static final int CPU_AVX2;
    private static final double[] EMPTY_DOUBLES = new double[0];
    private static final ThreadLocal<double[]> SLAB_SLOT_PACKED_SCRATCH =
            ThreadLocal.withInitial(() -> EMPTY_DOUBLES);
    private static final int DFC_SLAB_STACK = 192;

    private static final int OP_PUSH_CONST = 1;
    private static final int OP_PUSH_SLOT = 2;
    private static final int OP_COND_NEG_SCALE = 3;
    private static final int OP_Y_CLAMPED_GRADIENT = 4;
    private static final int OP_RANGE_CHOICE = 5;
    private static final int OP_BLOCK_X = 16;
    private static final int OP_BLOCK_Y = 17;
    private static final int OP_BLOCK_Z = 18;
    private static final int OP_HOIST = 19;
    private static final int OP_ADD = 32;
    private static final int OP_SUB = 33;
    private static final int OP_MUL = 34;
    private static final int OP_DIV = 35;
    private static final int OP_MIN = 36;
    private static final int OP_MAX = 37;
    private static final int OP_NEG = 48;
    private static final int OP_ABS = 49;
    private static final int OP_SQUARE = 50;
    private static final int OP_SQUEEZE = 51;

    static {
        LIB_OK = false;
        CPU_AVX2 = 0;
    }

    private DfcNativeBridge() {}

    /** {@code true} when the shared library loaded and JNI linked. */
    public static boolean isAvailable() {
        return LIB_OK;
    }

    /** Bitfield from native CPU probe; bit 0 = AVX2 available (x86_64). */
    public static boolean hasAvx2() {
        return LIB_OK && (CPU_AVX2 & 1) != 0;
    }

    public static boolean useAvx2Path() {
        return hasAvx2();
    }

    public static boolean canEvaluateSlabInner() {
        return LIB_OK || DfcOpenClRuntime.slabVmDispatchAvailable();
    }

    /** Non-null after {@link #isAvailable()} is first observed as {@code false}; describes missing bundle or {@link System#load} failure. */
    public static Throwable nativeLoadError() {
        return null;
    }

    public static native long allocNormalNoiseStack(double valueFactor, int n0, double scale0, double[] in0,
                                                    double[] amp0, byte[] perm0, double[] orig0, int n1, double scale1,
                                                    double[] in1, double[] amp1, byte[] perm1, double[] orig1);

    public static native void releaseNormalNoiseStack(long handle);

    /** One NormalNoise stack sample (both Perlin branches + value factor). */
    public static native double normalNoiseStackSample1(long handle, double cx, double cy, double cz);

    /**
     * Fills {@code outs[0..n)} with stack samples. Arrays must have length {@code >= n}.
     */
    public static native void normalNoiseStackBatch(long handle, double[] xs, double[] ys, double[] zs,
                                                    double[] outs, int n, boolean useAvx2);

    public static native long allocBlendedSpec(double[] doubles6, byte[] mainPerm, double[] mainOrig, byte[] minPerm,
                                               double[] minOrig, byte[] maxPerm, double[] maxOrig, byte[] mainPresent,
                                               byte[] minPresent, byte[] maxPresent);

    public static native void releaseBlendedSpec(long handle);

    public static native double blendedNoiseSample1(long handle, double bx, double by, double bz);

    public static native void blendedNoiseBatch(long handle, double[] xs, double[] ys, double[] zs, double[] outs,
                                                int n, boolean useAvx2);

    /** {@code slabLayout == 0}: Y-hoist xz-slab (flat index maps across the cell XZ plane). */
    public static final int SLAB_LAYOUT_Y_HOIST = 0;
    /** {@code slabLayout == 1}: XZ-hoist column (flat index maps down Y at fixed in-cell {@code columnXi}, {@code columnZi}). */
    public static final int SLAB_LAYOUT_XZ_HOIST = 1;

    /**
     * Vectorised lattice-inner postfix program (see {@code SlabInnerNativeProgram}). Writes {@code out[0..n)}.
     *
     * @param slabLayout {@link #SLAB_LAYOUT_Y_HOIST} or {@link #SLAB_LAYOUT_XZ_HOIST}
     * @param columnXi   in-cell X index when {@code slabLayout == SLAB_LAYOUT_XZ_HOIST}; ignored otherwise
     * @param columnZi   in-cell Z index when {@code slabLayout == SLAB_LAYOUT_XZ_HOIST}; ignored otherwise
     * @param columnCellHeight cell height when {@code slabLayout == SLAB_LAYOUT_XZ_HOIST} (must match {@code n}); ignored otherwise
     */
    public static void slabInnerEval(byte[] bytecode, double[] constants, double[][] slotRows, int firstNoiseBlockX,
                                     int firstNoiseBlockZ, int blockY, int cellWidth,
                                     int slabLayout, int columnXi, int columnZi, int columnCellHeight,
                                     double hoistValue, double[] out, int n) {
        if (bytecode == null || bytecode.length == 0 || out == null || n <= 0 || slotRows == null) {
            return;
        }
        if (constants == null) {
            constants = EMPTY_DOUBLES;
        }
        int slotCount = slotRows.length;
        double[] packedRows = packSlabSlotRows(slotRows, slotCount, n);
        if (DfcOpenClRuntime.tryEvalSlabInner(bytecode, constants, packedRows, slotCount, n,
                firstNoiseBlockX, firstNoiseBlockZ, blockY, cellWidth,
                slabLayout, columnXi, columnZi, columnCellHeight, hoistValue, out, n)) {
            return;
        }
        if (LIB_OK) {
            DfcOpenClStats.recordSlabFallbackJni();
            nativeSlabInnerEval(bytecode, constants, packedRows, slotCount, firstNoiseBlockX, firstNoiseBlockZ, blockY, cellWidth,
                    slabLayout, columnXi, columnZi, columnCellHeight, hoistValue, out, n);
            return;
        }
        DfcOpenClStats.recordSlabFallbackJava();
        javaSlabInnerEval(bytecode, constants, packedRows, slotCount, n, firstNoiseBlockX, firstNoiseBlockZ, blockY,
                cellWidth, slabLayout, columnXi, columnZi, columnCellHeight, hoistValue, out, n);
    }

    private static double[] packSlabSlotRows(double[][] slotRows, int slotCount, int rowLength) {
        int needed = slotCount * rowLength;
        double[] packed = SLAB_SLOT_PACKED_SCRATCH.get();
        if (packed.length < needed) {
            int capacity = Math.max(64, packed.length);
            while (capacity < needed && capacity < (Integer.MAX_VALUE >>> 1)) {
                capacity <<= 1;
            }
            if (capacity < needed) {
                capacity = needed;
            }
            packed = new double[capacity];
            SLAB_SLOT_PACKED_SCRATCH.set(packed);
        }
        for (int slot = 0; slot < slotCount; slot++) {
            double[] row = slotRows[slot];
            if (row == null || row.length < rowLength) {
                throw new IllegalArgumentException("slotRows[" + slot + "] is null or shorter than " + rowLength);
            }
            System.arraycopy(row, 0, packed, slot * rowLength, rowLength);
        }
        return packed;
    }

    private static void javaSlabInnerEval(byte[] bytecode, double[] constants, double[] packedRows, int slotCount,
                                          int slotRowStride, int firstNoiseBlockX, int firstNoiseBlockZ, int blockY,
                                          int cellWidth, int slabLayout, int columnXi, int columnZi,
                                          int columnCellHeight, double hoistValue, double[] out, int n) {
        if (cellWidth <= 0) {
            return;
        }
        for (int flat = 0; flat < n; flat++) {
            double bx;
            double by;
            double bz;
            if (slabLayout == SLAB_LAYOUT_Y_HOIST) {
                int ix = flat / cellWidth;
                int iz = flat - ix * cellWidth;
                bx = firstNoiseBlockX + ix;
                by = blockY;
                bz = firstNoiseBlockZ + iz;
            } else {
                if (columnCellHeight <= 0) {
                    return;
                }
                bx = firstNoiseBlockX + columnXi;
                by = blockY + (columnCellHeight - 1 - flat);
                bz = firstNoiseBlockZ + columnZi;
            }
            out[flat] = javaSlabInnerEvalOne(bytecode, constants, packedRows, slotCount, slotRowStride,
                    bx, by, bz, hoistValue, flat);
        }
    }

    private static double javaSlabInnerEvalOne(byte[] bytecode, double[] constants, double[] packedRows,
                                               int slotCount, int slotRowStride, double bx, double by, double bz,
                                               double hoistValue, int flatIndex) {
        double[] stack = new double[DFC_SLAB_STACK];
        int sp = 0;
        for (int pc = 0; pc < bytecode.length;) {
            int op = bytecode[pc++] & 0xFF;
            switch (op) {
                case OP_PUSH_CONST -> {
                    if (pc + 2 > bytecode.length) {
                        return 0.0D;
                    }
                    int idx = readU16Le(bytecode, pc);
                    pc += 2;
                    if (idx < 0 || idx >= constants.length || sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = constants[idx];
                }
                case OP_PUSH_SLOT -> {
                    if (pc >= bytecode.length) {
                        return 0.0D;
                    }
                    int slot = bytecode[pc++] & 0xFF;
                    if (slot < 0 || slot >= slotCount || slotRowStride <= 0 || sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = packedRows[slot * slotRowStride + flatIndex];
                }
                case OP_COND_NEG_SCALE -> {
                    if (pc + 2 > bytecode.length || sp < 1) {
                        return 0.0D;
                    }
                    int idx = readU16Le(bytecode, pc);
                    pc += 2;
                    if (idx < 0 || idx >= constants.length) {
                        return 0.0D;
                    }
                    double x = stack[--sp];
                    stack[sp++] = x > 0.0D ? x : x * constants[idx];
                }
                case OP_Y_CLAMPED_GRADIENT -> {
                    if (pc + 8 > bytecode.length || sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    int fromYIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    int toYIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    int fromValueIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    int toValueIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    if (fromYIdx < 0 || fromYIdx >= constants.length || toYIdx < 0 || toYIdx >= constants.length
                            || fromValueIdx < 0 || fromValueIdx >= constants.length
                            || toValueIdx < 0 || toValueIdx >= constants.length) {
                        return 0.0D;
                    }
                    stack[sp++] = javaClampedMap(by, constants[fromYIdx], constants[toYIdx],
                            constants[fromValueIdx], constants[toValueIdx]);
                }
                case OP_RANGE_CHOICE -> {
                    if (pc + 4 > bytecode.length || sp < 3) {
                        return 0.0D;
                    }
                    int minIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    int maxIdx = readU16Le(bytecode, pc);
                    pc += 2;
                    if (minIdx < 0 || minIdx >= constants.length || maxIdx < 0 || maxIdx >= constants.length) {
                        return 0.0D;
                    }
                    double whenOut = stack[--sp];
                    double whenIn = stack[--sp];
                    double input = stack[--sp];
                    stack[sp++] = input >= constants[minIdx] && input < constants[maxIdx] ? whenIn : whenOut;
                }
                case OP_BLOCK_X -> {
                    if (sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = bx;
                }
                case OP_BLOCK_Y -> {
                    if (sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = by;
                }
                case OP_BLOCK_Z -> {
                    if (sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = bz;
                }
                case OP_HOIST -> {
                    if (sp >= DFC_SLAB_STACK) {
                        return 0.0D;
                    }
                    stack[sp++] = hoistValue;
                }
                case OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MIN, OP_MAX -> {
                    if (sp < 2) {
                        return 0.0D;
                    }
                    double r = stack[--sp];
                    double l = stack[--sp];
                    stack[sp++] = switch (op) {
                        case OP_ADD -> l + r;
                        case OP_SUB -> l - r;
                        case OP_MUL -> l * r;
                        case OP_DIV -> l / r;
                        case OP_MIN -> javaMathMin(l, r);
                        case OP_MAX -> javaMathMax(l, r);
                        default -> 0.0D;
                    };
                }
                case OP_NEG, OP_ABS, OP_SQUARE, OP_SQUEEZE -> {
                    if (sp < 1) {
                        return 0.0D;
                    }
                    double x = stack[--sp];
                    stack[sp++] = switch (op) {
                        case OP_NEG -> -x;
                        case OP_ABS -> Math.abs(x);
                        case OP_SQUARE -> x * x;
                        case OP_SQUEEZE -> javaSqueeze(x);
                        default -> 0.0D;
                    };
                }
                default -> {
                    return 0.0D;
                }
            }
        }
        return sp == 1 ? stack[0] : 0.0D;
    }

    private static int readU16Le(byte[] bytecode, int pc) {
        return (bytecode[pc] & 0xFF) | ((bytecode[pc + 1] & 0xFF) << 8);
    }

    private static double javaMathMin(double l, double r) {
        if (Double.isNaN(l) || Double.isNaN(r)) {
            return Double.NaN;
        }
        if (l == 0.0D && r == 0.0D) {
            return Double.doubleToRawLongBits(l) < 0L || Double.doubleToRawLongBits(r) < 0L ? -0.0D : 0.0D;
        }
        return l <= r ? l : r;
    }

    private static double javaMathMax(double l, double r) {
        if (Double.isNaN(l) || Double.isNaN(r)) {
            return Double.NaN;
        }
        if (l == 0.0D && r == 0.0D) {
            return Double.doubleToRawLongBits(l) >= 0L || Double.doubleToRawLongBits(r) >= 0L ? 0.0D : -0.0D;
        }
        return l >= r ? l : r;
    }

    private static double javaSqueeze(double x) {
        double clamped = x < -1.0D ? -1.0D : (x > 1.0D ? 1.0D : x);
        return clamped / 2.0D - clamped * clamped * clamped / 24.0D;
    }

    private static double javaClampedMap(double value, double oldMin, double oldMax, double newMin, double newMax) {
        double delta = (value - oldMin) / (oldMax - oldMin);
        if (delta < 0.0D) {
            delta = 0.0D;
        } else if (delta > 1.0D) {
            delta = 1.0D;
        }
        return newMin + delta * (newMax - newMin);
    }

    private static native void nativeSlabInnerEval(byte[] bytecode, double[] constants, double[] packedSlotRows, int slotCount,
                                                   int firstNoiseBlockX, int firstNoiseBlockZ, int blockY, int cellWidth,
                                                   int slabLayout, int columnXi, int columnZi, int columnCellHeight,
                                                   double hoistValue, double[] out, int n);
}
