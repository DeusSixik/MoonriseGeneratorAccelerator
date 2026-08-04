package dev.sixik.generator_accelerator.common.density.compiler.cache;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.List;

public final class DfcRuntimeTelemetry {
    public static volatile boolean ENABLED = Boolean.getBoolean("dfc.telemetry.enabled");
    private static volatile long externInvokeCalls;

    private DfcRuntimeTelemetry() {
    }

    public record ClassStats(String className, long calls, long sampledNanos) {
    }

    public record GeneratedClassDebugStats(String className, long calls, long sampledNanos) {
    }

    public record Stats(boolean enabled, long externInvokeCalls, List<ClassStats> topExternClasses) {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void reset() {
        externInvokeCalls = 0L;
    }

    public static double computeExtern(DensityFunction extern, DensityFunction.FunctionContext context) {
        if (ENABLED) {
            externInvokeCalls++;
        }
        return extern.compute(context);
    }

    public static Stats snapshot() {
        return new Stats(ENABLED, externInvokeCalls, List.of());
    }

    public static List<GeneratedClassDebugStats> snapshotTopGeneratedDebugClasses() {
        return List.of();
    }

    public static String summary() {
        return "DFC telemetry: enabled=" + ENABLED + ", externInvokeCalls=" + externInvokeCalls;
    }
}