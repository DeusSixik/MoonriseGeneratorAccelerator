package dev.sixik.generator_accelerator.common.density.compiler.natives;

/**
 * Quarantined native bridge. JNI/off-heap density kernels are deliberately unavailable;
 * callers must use the pure Java fallback paths.
 */
public final class DfcNativeBridge {
    private static final String DISABLED_MESSAGE = "DFC native/JNI runtime is disabled; using pure Java density paths.";
    private static final UnsupportedOperationException DISABLED = new UnsupportedOperationException(DISABLED_MESSAGE);

    private DfcNativeBridge() {}

    /** {@code true} only when a shared native library is available. Always false in the Java-only runtime. */
    public static boolean isAvailable() {
        return false;
    }

    /** Native CPU probes are disabled with the native runtime. */
    public static boolean hasAvx2() {
        return false;
    }

    public static boolean useAvx2Path() {
        return false;
    }

    /** Describes why JNI/native paths are unavailable. */
    public static Throwable nativeLoadError() {
        return DISABLED;
    }

    public static long allocNormalNoiseStack(double valueFactor, int n0, double scale0, double[] in0,
                                             double[] amp0, byte[] perm0, double[] orig0, int n1, double scale1,
                                             double[] in1, double[] amp1, byte[] perm1, double[] orig1) {
        throw disabled();
    }

    public static void releaseNormalNoiseStack(long handle) {
        // No-op: Java-only runtime never creates native handles.
    }

    /** One NormalNoise stack sample (both Perlin branches + value factor). */
    public static double normalNoiseStackSample1(long handle, double cx, double cy, double cz) {
        throw disabled();
    }

    /** Fills {@code outs[0..n)} with stack samples. Arrays must have length {@code >= n}. */
    public static void normalNoiseStackBatch(long handle, double[] xs, double[] ys, double[] zs,
                                             double[] outs, int n, boolean useAvx2) {
        throw disabled();
    }

    public static long allocBlendedSpec(double[] doubles6, byte[] mainPerm, double[] mainOrig, byte[] minPerm,
                                        double[] minOrig, byte[] maxPerm, double[] maxOrig, byte[] mainPresent,
                                        byte[] minPresent, byte[] maxPresent) {
        throw disabled();
    }

    public static void releaseBlendedSpec(long handle) {
        // No-op: Java-only runtime never creates native handles.
    }

    public static double blendedNoiseSample1(long handle, double bx, double by, double bz) {
        throw disabled();
    }

    public static void blendedNoiseBatch(long handle, double[] xs, double[] ys, double[] zs, double[] outs,
                                         int n, boolean useAvx2) {
        throw disabled();
    }

    /** {@code slabLayout == 0}: Y-hoist xz-slab (flat index maps across the cell XZ plane). */
    public static final int SLAB_LAYOUT_Y_HOIST = 0;
    /** {@code slabLayout == 1}: XZ-hoist column (flat index maps down Y at fixed in-cell {@code columnXi}, {@code columnZi}). */
    public static final int SLAB_LAYOUT_XZ_HOIST = 1;

    /**
     * Native slab-inner evaluation is disabled. The caller's Java path remains authoritative.
     */
    public static void slabInnerEval(byte[] bytecode, double[] constants, double[][] slotRows, int firstNoiseBlockX,
                                     int firstNoiseBlockZ, int blockY, int cellWidth,
                                     int slabLayout, int columnXi, int columnZi, int columnCellHeight,
                                     double hoistValue, double[] out, int n) {
        // Deliberately empty: Java-only runtime never evaluates native slab programs.
    }

    private static UnsupportedOperationException disabled() {
        return new UnsupportedOperationException(DISABLED_MESSAGE);
    }
}
