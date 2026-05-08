package dev.sixik.generator_accelerator.common.density.compiler.natives;

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
    private static final ThreadLocal<double[]> SLAB_SLOT_PACKED_SCRATCH =
            ThreadLocal.withInitial(() -> new double[0]);

    static {
        boolean ok = NativeLibraryLoader.loadBundled();
        int avx = 0;
        if (ok) {
            try {
                avx = nativeQueryCpu();
            } catch (UnsatisfiedLinkError e) {
                ok = false;
            }
        }
        LIB_OK = ok;
        CPU_AVX2 = avx;
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

    /** Non-null after {@link #isAvailable()} is first observed as {@code false}; describes missing bundle or {@link System#load} failure. */
    public static Throwable nativeLoadError() {
        return NativeLibraryLoader.loadError();
    }

    private static native int nativeQueryCpu();

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
        if (!LIB_OK || bytecode == null || bytecode.length == 0 || out == null || n <= 0 || slotRows == null) {
            return;
        }
        if (constants == null) {
            constants = new double[0];
        }
        int slotCount = slotRows.length;
        double[] packedRows = packSlabSlotRows(slotRows, slotCount, n);
        nativeSlabInnerEval(bytecode, constants, packedRows, slotCount, firstNoiseBlockX, firstNoiseBlockZ, blockY, cellWidth,
                slabLayout, columnXi, columnZi, columnCellHeight, hoistValue, out, n);
    }

    private static double[] packSlabSlotRows(double[][] slotRows, int slotCount, int rowLength) {
        int needed = slotCount * rowLength;
        double[] packed = SLAB_SLOT_PACKED_SCRATCH.get();
        if (packed.length < needed) {
            packed = new double[needed];
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

    private static native void nativeSlabInnerEval(byte[] bytecode, double[] constants, double[] packedSlotRows, int slotCount,
                                                   int firstNoiseBlockX, int firstNoiseBlockZ, int blockY, int cellWidth,
                                                   int slabLayout, int columnXi, int columnZi, int columnCellHeight,
                                                   double hoistValue, double[] out, int n);
}
