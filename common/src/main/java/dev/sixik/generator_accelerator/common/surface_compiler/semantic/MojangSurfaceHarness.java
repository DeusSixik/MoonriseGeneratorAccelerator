package dev.sixik.generator_accelerator.common.surface_compiler.semantic;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MojangSurfaceHarness {
    private final VanillaSurfaceOracle oracle = new VanillaSurfaceOracle();

    public boolean canInvoke(VanillaInvocationContext context) {
        return context != null
                && context.surfaceSystem() != null
                && context.randomState() != null
                && context.biomeManager() != null
                && context.biomeRegistry() != null
                && context.worldContext() != null
                && context.chunk() != null
                && context.noiseChunk() != null
                && this.oracle.isAuthoritativeOracle(context.ruleSource());
    }

    public HarnessStatus status(VanillaInvocationContext context) {
        if (context == null) {
            return new HarnessStatus(false, "missing_context");
        }
        if (context.surfaceSystem() == null) {
            return new HarnessStatus(false, "missing_surface_system");
        }
        if (context.randomState() == null) {
            return new HarnessStatus(false, "missing_random_state");
        }
        if (context.biomeManager() == null) {
            return new HarnessStatus(false, "missing_biome_manager");
        }
        if (context.biomeRegistry() == null) {
            return new HarnessStatus(false, "missing_biome_registry");
        }
        if (context.worldContext() == null) {
            return new HarnessStatus(false, "missing_world_context");
        }
        if (context.chunk() == null) {
            return new HarnessStatus(false, "missing_chunk");
        }
        if (context.noiseChunk() == null) {
            return new HarnessStatus(false, "missing_noise_chunk");
        }
        VanillaSurfaceOracle.OracleStatus oracleStatus = this.oracle.status(context);
        if (!oracleStatus.authoritative()) {
            return new HarnessStatus(false, oracleStatus.reason());
        }
        return new HarnessStatus(true, "ready");
    }

    public HarnessComparison compare(VanillaInvocationContext context, SurfaceProgramIr candidate) {
        if (context == null) {
            return new HarnessComparison(false, false, "missing_context", "unavailable");
        }
        if (!this.oracle.isAuthoritativeOracle(context.ruleSource())) {
            return new HarnessComparison(false, false, "missing_rule_source", "unavailable");
        }
        VanillaSurfaceOracle.OracleComparison comparison = this.oracle.compare(context, candidate);
        return new HarnessComparison(comparison.comparable(), comparison.matched(), comparison.reason(), comparison.mode());
    }

    public Map<String, Object> snapshot(VanillaInvocationContext context) {
        HarnessStatus status = status(context);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", status.ready());
        out.put("reason", status.reason());
        out.put("oracle", this.oracle.snapshot(context));
        out.put("mode", "validation_only");
        return out;
    }

    public record HarnessStatus(boolean ready, String reason) {
    }

    public record HarnessComparison(boolean comparable, boolean matched, String reason, String mode) {
    }
}
