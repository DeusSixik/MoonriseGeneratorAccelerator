package dev.sixik.generator_accelerator.common.surface_compiler;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.EpochClassLoader;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.SyntheticCoverageRunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class SurfaceMetrics {
    public static volatile boolean ENABLED = Boolean.getBoolean("ga.surface.metrics");

    private static final AtomicLong PREPARES = new AtomicLong();
    private static final AtomicLong IDENTITY_CACHE_HITS = new AtomicLong();
    private static final AtomicLong IDENTITY_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CLASS_CIRCUIT_HITS = new AtomicLong();
    private static final AtomicLong CLASS_CIRCUIT_OPENS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong VANILLA_PLANS = new AtomicLong();
    private static final AtomicLong TIER0_PLANS = new AtomicLong();
    private static final AtomicLong TIER1_PLANS = new AtomicLong();
    private static final AtomicLong TIER2_PLANS = new AtomicLong();
    private static final AtomicLong TIER2_VECTOR_PLANS = new AtomicLong();
    private static final AtomicLong TIER2_GENERIC_PLANS = new AtomicLong();
    private static final AtomicLong TIER2_VECTOR_COMPILE_FAILURES = new AtomicLong();
    private static final AtomicLong QUARANTINED_PLANS = new AtomicLong();
    private static final AtomicLong QUARANTINES = new AtomicLong();
    private static final AtomicLong VANILLA_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER2_VECTOR_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER2_GENERIC_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER0_CERTIFIED = new AtomicLong();
    private static final AtomicLong TIER0_REJECTED = new AtomicLong();
    private static final AtomicLong TIER0_COMPILE_FAILURES = new AtomicLong();
    private static final AtomicLong TIER0_DIRECT_TEMPLATE_PLANS = new AtomicLong();
    private static final AtomicLong TIER0_VECTOR_TEMPLATE_PLANS = new AtomicLong();
    private static final AtomicLong TIER0_DIRECT_TEMPLATE_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER0_VECTOR_TEMPLATE_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER1_COMPILED = new AtomicLong();
    private static final AtomicLong TIER1_REJECTED = new AtomicLong();
    private static final AtomicLong TIER1_HYBRID_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER1_HYBRID_EXECUTION_FAILURES = new AtomicLong();
    private static final AtomicLong TIER1_SPECIALIZED_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER1_GENERIC_INTERPRETER_EXECUTIONS = new AtomicLong();
    private static final AtomicLong TIER_EXECUTION_FAILURES = new AtomicLong();
    private static final AtomicLong COVERAGE_PASSED = new AtomicLong();
    private static final AtomicLong COVERAGE_REJECTED = new AtomicLong();
    private static final AtomicLong COVERAGE_DIRECT_TEMPLATES = new AtomicLong();
    private static final AtomicLong COVERAGE_DOMAIN_TOTAL = new AtomicLong();
    private static final AtomicLong COVERAGE_DOMAIN_MAX = new AtomicLong();
    private static final AtomicLong COVERAGE_BRANCH_MISSING = new AtomicLong();
    private static final AtomicLong COVERAGE_ACTION_MISSING = new AtomicLong();
    private static final AtomicLong COVERAGE_TRACE_MISSING = new AtomicLong();
    private static final AtomicLong COVERAGE_TIER0_DISABLED = new AtomicLong();
    private static final AtomicLong COVERAGE_NON_LINEAR_TRACE = new AtomicLong();
    private static final AtomicLong COVERAGE_TOO_FEW_SAMPLES = new AtomicLong();
    private static final AtomicLong COVERAGE_TOO_FEW_DOMAINS = new AtomicLong();
    private static final AtomicLong COVERAGE_REASON_BRANCH_MISSING = new AtomicLong();
    private static final AtomicLong COVERAGE_REASON_ACTION_MISSING = new AtomicLong();
    private static final AtomicLong COVERAGE_REASON_TRACE_MISSING = new AtomicLong();
    private static final AtomicLong PREPARE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_NANOS = new AtomicLong();
    private static final AtomicLong EXECUTION_NANOS = new AtomicLong();
    private static final AtomicLong EXECUTION_COUNT = new AtomicLong();
    private static final AtomicLong EXECUTION_MAX_NANOS = new AtomicLong();
    private static final AtomicLong EXECUTION_OVER_100US = new AtomicLong();
    private static final AtomicLong EXECUTION_OVER_500US = new AtomicLong();
    private static final AtomicLong EXECUTION_OVER_1MS = new AtomicLong();

    private SurfaceMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void reset() {
        PREPARES.set(0L);
        IDENTITY_CACHE_HITS.set(0L);
        IDENTITY_CACHE_MISSES.set(0L);
        CLASS_CIRCUIT_HITS.set(0L);
        CLASS_CIRCUIT_OPENS.set(0L);
        CACHE_HITS.set(0L);
        CACHE_MISSES.set(0L);
        VANILLA_PLANS.set(0L);
        TIER0_PLANS.set(0L);
        TIER1_PLANS.set(0L);
        TIER2_PLANS.set(0L);
        TIER2_VECTOR_PLANS.set(0L);
        TIER2_GENERIC_PLANS.set(0L);
        TIER2_VECTOR_COMPILE_FAILURES.set(0L);
        QUARANTINED_PLANS.set(0L);
        QUARANTINES.set(0L);
        VANILLA_EXECUTIONS.set(0L);
        TIER2_VECTOR_EXECUTIONS.set(0L);
        TIER2_GENERIC_EXECUTIONS.set(0L);
        TIER0_CERTIFIED.set(0L);
        TIER0_REJECTED.set(0L);
        TIER0_COMPILE_FAILURES.set(0L);
        TIER0_DIRECT_TEMPLATE_PLANS.set(0L);
        TIER0_VECTOR_TEMPLATE_PLANS.set(0L);
        TIER0_DIRECT_TEMPLATE_EXECUTIONS.set(0L);
        TIER0_VECTOR_TEMPLATE_EXECUTIONS.set(0L);
        TIER1_COMPILED.set(0L);
        TIER1_REJECTED.set(0L);
        TIER1_HYBRID_EXECUTIONS.set(0L);
        TIER1_HYBRID_EXECUTION_FAILURES.set(0L);
        TIER1_SPECIALIZED_EXECUTIONS.set(0L);
        TIER1_GENERIC_INTERPRETER_EXECUTIONS.set(0L);
        TIER_EXECUTION_FAILURES.set(0L);
        COVERAGE_PASSED.set(0L);
        COVERAGE_REJECTED.set(0L);
        COVERAGE_DIRECT_TEMPLATES.set(0L);
        COVERAGE_DOMAIN_TOTAL.set(0L);
        COVERAGE_DOMAIN_MAX.set(0L);
        COVERAGE_BRANCH_MISSING.set(0L);
        COVERAGE_ACTION_MISSING.set(0L);
        COVERAGE_TRACE_MISSING.set(0L);
        COVERAGE_TIER0_DISABLED.set(0L);
        COVERAGE_NON_LINEAR_TRACE.set(0L);
        COVERAGE_TOO_FEW_SAMPLES.set(0L);
        COVERAGE_TOO_FEW_DOMAINS.set(0L);
        COVERAGE_REASON_BRANCH_MISSING.set(0L);
        COVERAGE_REASON_ACTION_MISSING.set(0L);
        COVERAGE_REASON_TRACE_MISSING.set(0L);
        PREPARE_NANOS.set(0L);
        VANILLA_NANOS.set(0L);
        EXECUTION_NANOS.set(0L);
        EXECUTION_COUNT.set(0L);
        EXECUTION_MAX_NANOS.set(0L);
        EXECUTION_OVER_100US.set(0L);
        EXECUTION_OVER_500US.set(0L);
        EXECUTION_OVER_1MS.set(0L);
    }

    public static void cacheHit() {
        CACHE_HITS.incrementAndGet();
    }

    public static void identityCacheHit() {
        IDENTITY_CACHE_HITS.incrementAndGet();
        cacheHit();
    }

    public static void identityCacheMiss() {
        IDENTITY_CACHE_MISSES.incrementAndGet();
    }

    public static void classCircuitHit() {
        CLASS_CIRCUIT_HITS.incrementAndGet();
    }

    public static void classCircuitOpen() {
        CLASS_CIRCUIT_OPENS.incrementAndGet();
    }

    public static void cacheMiss() {
        CACHE_MISSES.incrementAndGet();
    }

    public static void prepared(long startNanos) {
        PREPARES.incrementAndGet();
        record(PREPARE_NANOS, startNanos);
    }

    public static void tierSelected(SurfaceTier tier) {
        switch (tier) {
            case CERTIFIED_DIRECT_JIT -> TIER0_PLANS.incrementAndGet();
            case GUARDED_HYBRID_JIT -> TIER1_PLANS.incrementAndGet();
            case MASK_INTERPRETER -> TIER2_PLANS.incrementAndGet();
            case VANILLA_CLEAN_PATH -> VANILLA_PLANS.incrementAndGet();
            case QUARANTINED -> QUARANTINED_PLANS.incrementAndGet();
            case VALIDATION -> VANILLA_PLANS.incrementAndGet();
        }
    }

    public static void tier2VectorPlan() {
        TIER2_VECTOR_PLANS.incrementAndGet();
    }

    public static void tier2GenericPlan() {
        TIER2_GENERIC_PLANS.incrementAndGet();
    }

    public static void tier2VectorCompileFailure() {
        TIER2_VECTOR_COMPILE_FAILURES.incrementAndGet();
    }

    public static void tier2VectorExecution() {
        TIER2_VECTOR_EXECUTIONS.incrementAndGet();
    }

    public static void tier2GenericExecution() {
        TIER2_GENERIC_EXECUTIONS.incrementAndGet();
    }

    public static void quarantine(FallbackReason reason) {
        QUARANTINES.incrementAndGet();
    }

    public static void tier0Certified() {
        TIER0_CERTIFIED.incrementAndGet();
    }

    public static void tier0DirectTemplatePlan() {
        TIER0_DIRECT_TEMPLATE_PLANS.incrementAndGet();
    }

    public static void tier0VectorTemplatePlan() {
        TIER0_VECTOR_TEMPLATE_PLANS.incrementAndGet();
    }

    public static void tier0DirectTemplateExecution() {
        TIER0_DIRECT_TEMPLATE_EXECUTIONS.incrementAndGet();
    }

    public static void tier0VectorTemplateExecution() {
        TIER0_VECTOR_TEMPLATE_EXECUTIONS.incrementAndGet();
    }

    public static void tier0Rejected() {
        TIER0_REJECTED.incrementAndGet();
    }

    public static void tier0CompileFailure() {
        TIER0_COMPILE_FAILURES.incrementAndGet();
    }

    public static void tier1Compiled() {
        TIER1_COMPILED.incrementAndGet();
    }

    public static void tier1Rejected() {
        TIER1_REJECTED.incrementAndGet();
    }

    public static void tier1HybridExecution() {
        TIER1_HYBRID_EXECUTIONS.incrementAndGet();
    }

    public static void tier1HybridExecutionFailure() {
        TIER1_HYBRID_EXECUTION_FAILURES.incrementAndGet();
    }

    public static void tier1SpecializedExecution() {
        TIER1_SPECIALIZED_EXECUTIONS.incrementAndGet();
    }

    public static void tier1GenericInterpreterExecution() {
        TIER1_GENERIC_INTERPRETER_EXECUTIONS.incrementAndGet();
    }

    public static void tierExecutionFailure() {
        TIER_EXECUTION_FAILURES.incrementAndGet();
    }

    public static void coverage(SyntheticCoverageRunner.CoverageReport report) {
        if (report == null) {
            COVERAGE_REJECTED.incrementAndGet();
            return;
        }
        if (report.status() == SyntheticCoverageRunner.CoverageStatus.PASSED) {
            COVERAGE_PASSED.incrementAndGet();
        } else {
            COVERAGE_REJECTED.incrementAndGet();
            coverageRejection(report.rejectionReason());
        }
        if (report.directTemplate()) {
            COVERAGE_DIRECT_TEMPLATES.incrementAndGet();
        }
        SyntheticCoverageRunner.CoverageMatrix matrix = report.matrix();
        if (matrix != null) {
            COVERAGE_DOMAIN_TOTAL.addAndGet(matrix.domainCount());
            COVERAGE_DOMAIN_MAX.accumulateAndGet(matrix.domainCount(), Math::max);
            if (!matrix.hasBranchCoverage()) {
                COVERAGE_BRANCH_MISSING.incrementAndGet();
            }
            if (!matrix.hasMaterialActionCoverage()) {
                COVERAGE_ACTION_MISSING.incrementAndGet();
            }
            if (!matrix.hasStateTraceCoverage()) {
                COVERAGE_TRACE_MISSING.incrementAndGet();
            }
        }
    }

    public static void vanillaExecution(long startNanos) {
        VANILLA_EXECUTIONS.incrementAndGet();
        record(VANILLA_NANOS, startNanos);
    }

    public static void optimizedExecution(long startNanos) {
        if (!ENABLED || startNanos == 0L) {
            return;
        }
        long nanos = System.nanoTime() - startNanos;
        EXECUTION_COUNT.incrementAndGet();
        EXECUTION_NANOS.addAndGet(nanos);
        EXECUTION_MAX_NANOS.accumulateAndGet(nanos, Math::max);
        if (nanos >= 100_000L) {
            EXECUTION_OVER_100US.incrementAndGet();
        }
        if (nanos >= 500_000L) {
            EXECUTION_OVER_500US.incrementAndGet();
        }
        if (nanos >= 1_000_000L) {
            EXECUTION_OVER_1MS.incrementAndGet();
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("preparedPlans", PREPARES.get());
        out.put("identityCacheHits", IDENTITY_CACHE_HITS.get());
        out.put("identityCacheMisses", IDENTITY_CACHE_MISSES.get());
        out.put("classCircuitHits", CLASS_CIRCUIT_HITS.get());
        out.put("classCircuitOpens", CLASS_CIRCUIT_OPENS.get());
        out.put("cacheHits", CACHE_HITS.get());
        out.put("cacheMisses", CACHE_MISSES.get());
        out.put("tier0Plans", TIER0_PLANS.get());
        out.put("tier1Plans", TIER1_PLANS.get());
        out.put("tier2Plans", TIER2_PLANS.get());
        Map<String, Object> tier2Backend = new LinkedHashMap<>();
        tier2Backend.put("vectorPlans", TIER2_VECTOR_PLANS.get());
        tier2Backend.put("genericPlans", TIER2_GENERIC_PLANS.get());
        tier2Backend.put("vectorCompileFailures", TIER2_VECTOR_COMPILE_FAILURES.get());
        tier2Backend.put("vectorExecutions", TIER2_VECTOR_EXECUTIONS.get());
        tier2Backend.put("genericExecutions", TIER2_GENERIC_EXECUTIONS.get());
        out.put("tier2Backend", tier2Backend);
        out.put("vanillaPlans", VANILLA_PLANS.get());
        out.put("quarantinedPlans", QUARANTINED_PLANS.get());
        out.put("quarantineEvents", QUARANTINES.get());
        out.put("vanillaExecutions", VANILLA_EXECUTIONS.get());
        out.put("tier0Certified", TIER0_CERTIFIED.get());
        out.put("tier0Rejected", TIER0_REJECTED.get());
        out.put("tier0CompileFailures", TIER0_COMPILE_FAILURES.get());
        Map<String, Object> tier0Backend = new LinkedHashMap<>();
        tier0Backend.put("directTemplatePlans", TIER0_DIRECT_TEMPLATE_PLANS.get());
        tier0Backend.put("vectorTemplatePlans", TIER0_VECTOR_TEMPLATE_PLANS.get());
        tier0Backend.put("directTemplateExecutions", TIER0_DIRECT_TEMPLATE_EXECUTIONS.get());
        tier0Backend.put("vectorTemplateExecutions", TIER0_VECTOR_TEMPLATE_EXECUTIONS.get());
        out.put("tier0Backend", tier0Backend);
        out.put("tier1Compiled", TIER1_COMPILED.get());
        out.put("tier1Rejected", TIER1_REJECTED.get());
        Map<String, Object> tier1Backend = new LinkedHashMap<>();
        tier1Backend.put("hybridExecutions", TIER1_HYBRID_EXECUTIONS.get());
        tier1Backend.put("hybridExecutionFailures", TIER1_HYBRID_EXECUTION_FAILURES.get());
        tier1Backend.put("specializedExecutions", TIER1_SPECIALIZED_EXECUTIONS.get());
        tier1Backend.put("genericInterpreterExecutions", TIER1_GENERIC_INTERPRETER_EXECUTIONS.get());
        out.put("tier1Backend", tier1Backend);
        out.put("tierExecutionFailures", TIER_EXECUTION_FAILURES.get());
        out.put("cacheEntries", SurfaceCompilerCaches.store().size());
        out.put("liveKernelClassLoaders", EpochClassLoader.liveLoaderCount());
        out.put("liveKernelEpochs", EpochClassLoader.liveEpochs());

        Map<String, Object> nanos = new LinkedHashMap<>();
        nanos.put("prepare", PREPARE_NANOS.get());
        nanos.put("vanilla", VANILLA_NANOS.get());
        nanos.put("optimizedExecution", EXECUTION_NANOS.get());
        out.put("nanos", nanos);

        long optimizedExecutions = EXECUTION_COUNT.get();
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("optimizedExecutions", optimizedExecutions);
        latency.put("avgOptimizedNs", optimizedExecutions == 0L ? 0.0 : EXECUTION_NANOS.get() / (double) optimizedExecutions);
        latency.put("maxOptimizedNs", EXECUTION_MAX_NANOS.get());
        latency.put("over100us", EXECUTION_OVER_100US.get());
        latency.put("over500us", EXECUTION_OVER_500US.get());
        latency.put("over1ms", EXECUTION_OVER_1MS.get());
        out.put("latency", latency);

        long coverageRuns = COVERAGE_PASSED.get() + COVERAGE_REJECTED.get();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("runs", coverageRuns);
        coverage.put("passed", COVERAGE_PASSED.get());
        coverage.put("rejected", COVERAGE_REJECTED.get());
        coverage.put("directTemplates", COVERAGE_DIRECT_TEMPLATES.get());
        coverage.put("avgDomains", coverageRuns == 0L ? 0.0 : COVERAGE_DOMAIN_TOTAL.get() / (double) coverageRuns);
        coverage.put("maxDomains", COVERAGE_DOMAIN_MAX.get());
        coverage.put("missingBranchCoverage", COVERAGE_BRANCH_MISSING.get());
        coverage.put("missingMaterialActionCoverage", COVERAGE_ACTION_MISSING.get());
        coverage.put("missingStateTraceCoverage", COVERAGE_TRACE_MISSING.get());
        Map<String, Object> rejectionReasons = new LinkedHashMap<>();
        rejectionReasons.put("tier0Disabled", COVERAGE_TIER0_DISABLED.get());
        rejectionReasons.put("nonLinearStateTrace", COVERAGE_NON_LINEAR_TRACE.get());
        rejectionReasons.put("tooFewSyntheticSamples", COVERAGE_TOO_FEW_SAMPLES.get());
        rejectionReasons.put("tooFewDomains", COVERAGE_TOO_FEW_DOMAINS.get());
        rejectionReasons.put("missingBranchCoverage", COVERAGE_REASON_BRANCH_MISSING.get());
        rejectionReasons.put("missingMaterialActionCoverage", COVERAGE_REASON_ACTION_MISSING.get());
        rejectionReasons.put("missingStateTraceCoverage", COVERAGE_REASON_TRACE_MISSING.get());
        coverage.put("rejectionReasons", rejectionReasons);
        out.put("coverage", coverage);
        return out;
    }

    private static void coverageRejection(SyntheticCoverageRunner.RejectionReason reason) {
        switch (reason == null ? SyntheticCoverageRunner.RejectionReason.NONE : reason) {
            case TIER0_DISABLED -> COVERAGE_TIER0_DISABLED.incrementAndGet();
            case NON_LINEAR_STATE_TRACE -> COVERAGE_NON_LINEAR_TRACE.incrementAndGet();
            case TOO_FEW_SYNTHETIC_SAMPLES -> COVERAGE_TOO_FEW_SAMPLES.incrementAndGet();
            case TOO_FEW_DOMAINS -> COVERAGE_TOO_FEW_DOMAINS.incrementAndGet();
            case MISSING_BRANCH_COVERAGE -> COVERAGE_REASON_BRANCH_MISSING.incrementAndGet();
            case MISSING_MATERIAL_ACTION_COVERAGE -> COVERAGE_REASON_ACTION_MISSING.incrementAndGet();
            case MISSING_STATE_TRACE_COVERAGE -> COVERAGE_REASON_TRACE_MISSING.incrementAndGet();
            case NONE -> {
            }
        }
    }

    private static void record(AtomicLong counter, long startNanos) {
        if (ENABLED && startNanos != 0L) {
            counter.addAndGet(System.nanoTime() - startNanos);
        }
    }
}
