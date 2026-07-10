package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.GeneratedKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.facts.SurfaceFacts;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.SurfaceCertification;

public record SurfaceExecutionPlan(
        FingerprintCacheKey key,
        SurfaceTier tier,
        SurfaceCommitMode commitMode,
        SurfaceProgramIr ir,
        SurfaceFacts facts,
        FallbackReason fallbackReason,
        SurfaceCertification certification,
        GeneratedKernel kernel
) {
    public SurfaceExecutionPlan(
            FingerprintCacheKey key,
            SurfaceTier tier,
            SurfaceCommitMode commitMode,
            SurfaceProgramIr ir,
            SurfaceFacts facts,
            FallbackReason fallbackReason,
            GeneratedKernel kernel
    ) {
        this(key, tier, commitMode, ir, facts, fallbackReason, null, kernel);
    }

    public SurfaceExecutionPlan(
            FingerprintCacheKey key,
            SurfaceTier tier,
            SurfaceCommitMode commitMode,
            SurfaceProgramIr ir,
            SurfaceFacts facts,
            FallbackReason fallbackReason
    ) {
        this(key, tier, commitMode, ir, facts, fallbackReason, null, null);
    }

    public SurfaceExecutionPlan withCertification(SurfaceCertification certification) {
        return new SurfaceExecutionPlan(this.key, this.tier, this.commitMode, this.ir, this.facts, this.fallbackReason, certification, this.kernel);
    }

    public boolean useVanillaCleanPath() {
        return this.tier == SurfaceTier.VANILLA_CLEAN_PATH || this.tier == SurfaceTier.QUARANTINED;
    }

    public boolean hasKernel() {
        return this.kernel != null;
    }
}
