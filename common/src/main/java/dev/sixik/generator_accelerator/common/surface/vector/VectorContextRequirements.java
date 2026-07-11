package dev.sixik.generator_accelerator.common.surface.vector;

public final class VectorContextRequirements {
    public static final int SURFACE_HEIGHTS = 1;
    public static final int SURFACE_BIOMES = 1 << 1;
    public static final int SURFACE_DEPTHS = 1 << 2;
    public static final int SECONDARY_SURFACE_NOISE = 1 << 3;
    public static final int PRELIMINARY_SURFACE = 1 << 4;

    private VectorContextRequirements() {
    }
}
