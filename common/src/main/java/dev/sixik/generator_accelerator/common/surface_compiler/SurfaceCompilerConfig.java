package dev.sixik.generator_accelerator.common.surface_compiler;

/**
 * User-facing switches for Surface Compiler 2.0.
 *
 * <p>The clean rewrite keeps direct writes behind certification. Tier 0 is
 * enabled by default, but only certified direct templates can reach it.
 */
public final class SurfaceCompilerConfig {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.enabled", "true"));
    public static final boolean PARANOID = Boolean.getBoolean("ga.surface.compiler.paranoid");
    public static final boolean DEBUG = Boolean.getBoolean("ga.surface.compiler.debug");
    public static final boolean FORCE_VANILLA = Boolean.getBoolean("ga.surface.compiler.forceVanilla");
    public static final boolean ENABLE_TIER2_INTERPRETER = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.tier2", "true"));
    public static final boolean ENABLE_TIER1_HYBRID = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.tier1", "true"));
    public static final boolean ENABLE_TIER0_DIRECT = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.tier0", "true"));
    public static final int CACHE_MAX_SIZE = Integer.getInteger("ga.surface.compiler.cache.maxSize", 512);
    public static final int SYNTHETIC_COVERAGE_SAMPLES = Integer.getInteger("ga.surface.compiler.coverage.samples", 64);
    public static final int SYNTHETIC_COVERAGE_MIN_DOMAINS = Integer.getInteger("ga.surface.compiler.coverage.minDomains", 12);

    private SurfaceCompilerConfig() {
    }
}
