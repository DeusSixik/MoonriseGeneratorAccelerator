package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.halo.HaloPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotPlan;

import java.util.Set;

public record SurfaceFacts(
        boolean safeForInterpreter,
        boolean safeForHybrid,
        boolean directWriteCertified,
        boolean hasOpaqueCallouts,
        boolean hasStateTokens,
        boolean requiresCow,
        boolean requiresTraceValidation,
        int opCount,
        int statefulOpCount,
        Set<String> domains,
        SnapshotPlan snapshotPlan,
        HaloPlan haloPlan
) {
}
