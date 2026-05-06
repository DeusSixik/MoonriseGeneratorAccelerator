package dev.sixik.generator_accelerator.common.surface.compiler;

public final class SurfaceCompilerConfig {
    public static final boolean METRICS = Boolean.getBoolean("ga.surface.metrics");
    public static final boolean IR = Boolean.parseBoolean(System.getProperty("ga.surface.compiler.ir", "true"));
    public static final boolean DAG = Boolean.getBoolean("ga.surface.compiler.dag");
    public static final boolean COLUMN_INTERVAL = Boolean.getBoolean("ga.surface.compiler.columnInterval");
    public static final boolean CODEGEN = Boolean.getBoolean("ga.surface.compiler.codegen");
    public static final boolean DUMP = Boolean.getBoolean("ga.surface.compiler.dump");

    private SurfaceCompilerConfig() {
    }
}
