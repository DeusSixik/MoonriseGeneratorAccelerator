package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenSafetyTier;

import java.util.List;
import java.util.Map;

public record WorldgenGeneratedPlan(
        String unitId,
        WorldgenOptimizationPattern pattern,
        WorldgenFastPathKind fastPathKind,
        WorldgenSafetyTier targetTier,
        List<WorldgenOptimizerGuard> guards,
        Map<String, String> attributes,
        int estimatedCost,
        String reason
) {
    public boolean enabled() {
        return pattern != WorldgenOptimizationPattern.NONE && fastPathKind != WorldgenFastPathKind.NONE;
    }
}
