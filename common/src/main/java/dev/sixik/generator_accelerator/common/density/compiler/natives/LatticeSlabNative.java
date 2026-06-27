package dev.sixik.generator_accelerator.common.density.compiler.natives;

/**
 * Marker type: slab batching is implemented in {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen}
 * ({@code fillArray} → per-Y coordinate fill + {@link DfcNativeBridge#normalNoiseStackBatch};
 * see {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.SlabNativeBatchPlan}).
 */
public final class LatticeSlabNative {

    private LatticeSlabNative() {}

    /** Unused; slab path is always emitted from {@code Codegen} when {@link SlabNativeBatchPlan} applies. */
    public static boolean tryNativeSlabFill() {
        return false;
    }
}
