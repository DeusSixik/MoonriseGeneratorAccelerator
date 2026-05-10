package dev.sixik.generator_accelerator.common.surface.compiler;

public final class SurfaceCompilerConfig {
    public static final boolean METRICS = Boolean.getBoolean("ga.surface.metrics");
    public static final boolean IR = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.ir", "true"));
    public static final boolean DAG = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.dag", "true"));
    public static final boolean COLUMN_INTERVAL = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.columnInterval", "true"));
    public static final boolean DUMP = Boolean.getBoolean("ga.surface.compiler.dump");

    private SurfaceCompilerConfig() {
    }
}
