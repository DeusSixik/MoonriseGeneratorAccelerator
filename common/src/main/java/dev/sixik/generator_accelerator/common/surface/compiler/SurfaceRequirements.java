package dev.sixik.generator_accelerator.common.surface.compiler;

public final class SurfaceRequirements {
    public static final int BIOME = 1;
    public static final int STONE_DEPTH = 1 << 1;
    public static final int WATER = 1 << 2;
    public static final int SURFACE_DEPTH = 1 << 3;
    public static final int PRELIMINARY_SURFACE = 1 << 4;
    public static final int TEMPERATURE = 1 << 5;
    public static final int NOISE = 1 << 6;
    public static final int RANDOM = 1 << 7;
    public static final int SLOPE = 1 << 8;
    public static final int FALLBACK = 1 << 9;
    public static final int SECONDARY_SURFACE = 1 << 10;

    private SurfaceRequirements() {
    }
}
