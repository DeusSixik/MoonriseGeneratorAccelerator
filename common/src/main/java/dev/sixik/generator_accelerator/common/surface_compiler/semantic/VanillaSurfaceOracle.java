package dev.sixik.generator_accelerator.common.surface_compiler.semantic;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.VanillaParityComparator;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VanillaSurfaceOracle {
    private final VanillaParityComparator parity = new VanillaParityComparator();

    public boolean isAuthoritativeOracle(SurfaceRules.RuleSource source) {
        return source != null;
    }

    public OracleStatus status(VanillaInvocationContext context) {
        if (context == null) {
            return new OracleStatus(false, "missing_context");
        }
        if (!isAuthoritativeOracle(context.ruleSource())) {
            return new OracleStatus(false, "missing_rule_source");
        }
        return new OracleStatus(true, "mojang_rule_source_available");
    }

    public OracleComparison compare(VanillaInvocationContext context, SurfaceProgramIr candidate) {
        OracleStatus status = status(context);
        if (!status.authoritative()) {
            return new OracleComparison(false, false, status.reason(), false, "unavailable");
        }
        if (candidate == null) {
            return new OracleComparison(false, false, "missing_candidate_ir", true, "debug_ir");
        }
        boolean equivalent = this.parity.equivalent(context.ruleSource(), candidate);
        return new OracleComparison(true, equivalent, equivalent ? "matched" : "mismatch", true, "debug_ir");
    }

    public Map<String, Object> snapshot(VanillaInvocationContext context) {
        OracleStatus status = status(context);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authoritative", status.authoritative());
        out.put("reason", status.reason());
        out.put("hotPath", false);
        out.put("baselineInterpreterIsOracle", false);
        out.put("comparisonMode", "debug_ir");
        out.put("realChunkMutation", false);
        return out;
    }

    public record OracleStatus(boolean authoritative, String reason) {
    }

    public record OracleComparison(boolean comparable, boolean matched, String reason, boolean authoritative, String mode) {
    }
}
