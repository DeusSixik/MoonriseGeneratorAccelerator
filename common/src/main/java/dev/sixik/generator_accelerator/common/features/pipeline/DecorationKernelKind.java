package dev.sixik.generator_accelerator.common.features.pipeline;

public enum DecorationKernelKind {
    NATIVE_ORE(true, false, false),
    NATIVE_SCATTERED_ORE(true, false, false),
    NATIVE_RANDOM_PATCH_SIMPLE(true, false, false),
    NATIVE_RANDOM_PATCH_SELECTOR(true, false, false),
    NATIVE_SELECTOR_SIMPLE(true, false, false),
    NATIVE_SIMPLE_BLOCK(true, false, false),
    NATIVE_DISK(true, false, false),
    NATIVE_BLOCK_COLUMN(true, false, false),
    NATIVE_PLANT_WATER(true, false, false),
    NATIVE_SPRING(true, false, false),
    NATIVE_SNOW_FREEZE(true, false, false),
    NATIVE_SCULK_PATCH(true, false, false),
    NATIVE_TREE(true, false, false),
    PARTIAL_NATIVE_PLACEMENT(false, true, false),
    PARTIAL_NATIVE_DESCRIPTOR_GATED(false, true, false),
    VANILLA_FALLBACK(false, false, true);

    private final boolean nativeKernel;
    private final boolean partialNative;
    private final boolean fallback;

    DecorationKernelKind(boolean nativeKernel, boolean partialNative, boolean fallback) {
        this.nativeKernel = nativeKernel;
        this.partialNative = partialNative;
        this.fallback = fallback;
    }

    public boolean isNativeKernel() {
        return this.nativeKernel;
    }

    public boolean isPartialNative() {
        return this.partialNative;
    }

    public boolean isFallback() {
        return this.fallback;
    }
}
