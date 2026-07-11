package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerCaches;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceCompilerConfig;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.DirectJitBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.GeneratedKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.HybridJitBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.interpreter.MaskInterpreterBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.template.DirectTemplateSurfaceKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.vector.VectorSurfaceKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterRegistry;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.KnownUnsafeRuleRegistry;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.ModFallbackReporter;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.SurfaceAdapter;
import dev.sixik.generator_accelerator.common.surface_compiler.cow.SectionCowManager;
import dev.sixik.generator_accelerator.common.surface_compiler.facts.SurfaceFacts;
import dev.sixik.generator_accelerator.common.surface_compiler.facts.SurfaceFactsAnalyzer;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceFingerprint;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceIrBuilder;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceRuleClassifier;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceRuleScanner;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.SurfaceOptimizer;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotResolver;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SurfaceReadSnapshot;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.SurfaceTelemetry;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.QuarantineManager;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.SurfaceCertification;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.SyntheticCoverageRunner;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.TranslationValidator;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SurfaceRuntime {
    public static final String RUNTIME_BINDING_VERSION = "verified-stateful-runtime-v1";

    private static final AdapterRegistry ADAPTERS = new AdapterRegistry();
    private static final KnownUnsafeRuleRegistry UNSAFE_RULES = new KnownUnsafeRuleRegistry();
    private static final SurfaceRuleScanner SCANNER = new SurfaceRuleScanner(ADAPTERS);
    private static final SurfaceIrBuilder IR_BUILDER = new SurfaceIrBuilder();
    private static final SurfaceFactsAnalyzer FACTS_ANALYZER = new SurfaceFactsAnalyzer();
    private static final SurfaceOptimizer OPTIMIZER = new SurfaceOptimizer();
    private static final SnapshotResolver SNAPSHOTS = new SnapshotResolver();
    private static final TranslationValidator TRANSLATION_VALIDATOR = new TranslationValidator();
    private static final SyntheticCoverageRunner COVERAGE_RUNNER = new SyntheticCoverageRunner();
    private static final MaskInterpreterBackend MASK_INTERPRETER = new MaskInterpreterBackend();
    private static final DirectJitBackend DIRECT_JIT = new DirectJitBackend();
    private static final HybridJitBackend HYBRID_JIT = new HybridJitBackend();
    private static final QuarantineManager QUARANTINE = new QuarantineManager();
    private static final SurfaceTelemetry TELEMETRY = new SurfaceTelemetry();
    private static final ModFallbackReporter FALLBACK_REPORTER = new ModFallbackReporter();
    private static final Cache<SurfaceRules.RuleSource, SurfaceExecutionPlan> IDENTITY_PLAN_CACHE = Caffeine.newBuilder()
            .weakKeys()
            .maximumSize(Math.max(16L, SurfaceCompilerConfig.CACHE_MAX_SIZE * 2L))
            .build();
    private static final ConcurrentMap<String, FallbackReason> CLASS_CIRCUIT_BREAKERS = new ConcurrentHashMap<>();

    private SurfaceRuntime() {
    }

    public static SurfaceExecutionPlan prepare(SurfaceRules.RuleSource ruleSource) {
        long start = SurfaceMetrics.startTimer();

        String rootClassName = rootClassName(ruleSource);
        FallbackReason classCircuit = CLASS_CIRCUIT_BREAKERS.get(rootClassName);
        if (classCircuit != null) {
            SurfaceMetrics.classCircuitHit();
            SurfaceExecutionPlan plan = vanillaPlan(null, null, null, classCircuit);
            SurfaceMetrics.tierSelected(plan.tier());
            SurfaceMetrics.prepared(start);
            return plan;
        }

        SurfaceExecutionPlan identityCached = identityGet(ruleSource);
        if (identityCached != null) {
            if (identityCached.key() != null && QUARANTINE.isQuarantined(identityCached.key())) {
                identityRemove(ruleSource);
            } else {
            SurfaceMetrics.identityCacheHit();
            SurfaceMetrics.tierSelected(identityCached.tier());
            recordPlan(identityCached);
            SurfaceMetrics.prepared(start);
            return identityCached;
            }
        }

        SurfaceMetrics.identityCacheMiss();
        FingerprintCacheKey key = SurfaceFingerprint.keyFor(ruleSource);

        if (QUARANTINE.isQuarantined(key)) {
            SurfaceMetrics.cacheMiss();
            SurfaceExecutionPlan plan = vanillaPlan(key, null, null, FallbackReason.QUARANTINED);
            SurfaceCompilerCaches.store().put(key, plan);
            SurfaceMetrics.tierSelected(plan.tier());
            recordPlan(plan);
            identityPut(ruleSource, plan);
            SurfaceMetrics.prepared(start);
            return plan;
        }

        SurfaceExecutionPlan cached = SurfaceCompilerCaches.store().get(key);
        if (cached != null) {
            SurfaceMetrics.cacheHit();
            SurfaceMetrics.tierSelected(cached.tier());
            recordPlan(cached);
            identityPut(ruleSource, cached);
            SurfaceMetrics.prepared(start);
            return cached;
        }

        SurfaceMetrics.cacheMiss();
        SurfaceExecutionPlan plan = buildPlan(key, ruleSource);
        SurfaceCompilerCaches.store().put(key, plan);
        SurfaceMetrics.tierSelected(plan.tier());
        recordPlan(plan);
        identityPut(ruleSource, plan);
        SurfaceMetrics.prepared(start);
        return plan;
    }

    public static void clearCaches() {
        SurfaceCompilerCaches.clear();
        CLASS_CIRCUIT_BREAKERS.clear();
        IDENTITY_PLAN_CACHE.invalidateAll();
        TELEMETRY.reset();
        FALLBACK_REPORTER.clear();
    }

    public static int identityCacheSize() {
        IDENTITY_PLAN_CACHE.cleanUp();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, IDENTITY_PLAN_CACHE.estimatedSize()));
    }

    public static AdapterRegistry adapters() {
        return ADAPTERS;
    }

    public static KnownUnsafeRuleRegistry unsafeRules() {
        return UNSAFE_RULES;
    }

    public static SurfaceTelemetry telemetry() {
        return TELEMETRY;
    }

    public static ModFallbackReporter fallbackReporter() {
        return FALLBACK_REPORTER;
    }

    public static boolean execute(
            SurfaceExecutionPlan plan,
            SurfaceSystem surfaceSystem,
            RandomState randomState,
            BiomeManager biomeManager,
            Registry<Biome> biomeRegistry,
            boolean useLegacyRandomSource,
            WorldGenerationContext worldContext,
            ChunkAccess chunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource
    ) {
        if (plan == null || plan.useVanillaCleanPath()) {
            return false;
        }

        long start = SurfaceMetrics.startTimer();

        SurfaceWorkerState workerState = SurfaceWorkerState.acquire();
        SurfaceReadSnapshot snapshot = SNAPSHOTS.resolve(plan.facts() == null ? null : plan.facts().snapshotPlan(), chunk);
        if (!snapshot.available() && plan.facts() != null && plan.facts().snapshotPlan().fallbackIfUnavailable()) {
            QUARANTINE.quarantine(plan.key(), FallbackReason.SNAPSHOT_UNAVAILABLE);
            identityRemove(ruleSource);
            SurfaceMetrics.tierExecutionFailure();
            return false;
        }

        boolean usesCow = plan.commitMode() == SurfaceCommitMode.COW_SHADOW || plan.commitMode() == SurfaceCommitMode.COW_VERIFY;
        SectionCowManager cowManager = usesCow ? new SectionCowManager(chunk) : null;
        SurfaceExecutionContext context = new SurfaceExecutionContext(
                surfaceSystem,
                randomState,
                biomeManager,
                biomeRegistry,
                useLegacyRandomSource,
                worldContext,
                chunk,
                noiseChunk,
                ruleSource,
                workerState,
                snapshot,
                cowManager
        );

        try {
            boolean executed = switch (plan.tier()) {
                case MASK_INTERPRETER, VALIDATION -> executeTier2(plan, context);
                case GUARDED_HYBRID_JIT -> executeTier1(plan, context);
                case CERTIFIED_DIRECT_JIT -> executeTier0(plan, context);
                case VANILLA_CLEAN_PATH, QUARANTINED -> false;
            };
            if (!executed) {
                if (cowManager != null) {
                    cowManager.discard();
                }
                QUARANTINE.quarantine(plan.key(), FallbackReason.EXECUTION_FAILURE);
                identityRemove(ruleSource);
                SurfaceMetrics.tierExecutionFailure();
                recordExecution(plan, false);
                return false;
            }
            if (cowManager != null) {
                cowManager.commit();
            }
            recordExecution(plan, true);
            SurfaceMetrics.optimizedExecution(start);
            return true;
        } catch (RuntimeException throwable) {
            if (cowManager != null) {
                cowManager.discard();
            }
            QUARANTINE.quarantine(plan.key(), FallbackReason.EXECUTION_FAILURE);
            identityRemove(ruleSource);
            SurfaceMetrics.tierExecutionFailure();
            recordExecution(plan, false);
            SurfaceMetrics.optimizedExecution(start);
            return false;
        }
    }

    private static SurfaceExecutionPlan buildPlan(FingerprintCacheKey key, SurfaceRules.RuleSource ruleSource) {
        if (!SurfaceCompilerConfig.ENABLED || SurfaceCompilerConfig.FORCE_VANILLA) {
            return vanillaPlan(key, null, null, FallbackReason.DISABLED);
        }
        SurfaceRuleScanner.SurfaceScanResult scan = SCANNER.scan(ruleSource);
        recordOpaqueNodes(scan);
        SurfaceRuleClassifier.Classification classification = new SurfaceRuleClassifier(ADAPTERS, UNSAFE_RULES).classify(scan);
        recordAdapterScan(scan, classification);
        SurfaceProgramIr ir = IR_BUILDER.build(scan);
        SurfaceFacts facts = FACTS_ANALYZER.analyze(ir);

        if (!classification.compilerEligible() || !facts.safeForInterpreter()) {
            QUARANTINE.quarantine(key, classification.fallbackReason());
            openClassCircuitBreaker(scan.rootClassName(), classification.fallbackReason());
            FALLBACK_REPORTER.report(key.structuralRuleHash(), classification.fallbackReason());
            return vanillaPlan(key, ir, facts, classification.fallbackReason());
        }

        SurfaceProgramIr optimized = OPTIMIZER.optimize(ir);
        if (!TRANSLATION_VALIDATOR.validate(ir, optimized)) {
            QUARANTINE.quarantine(key, FallbackReason.VALIDATION_REQUIRED);
            FALLBACK_REPORTER.report(key.structuralRuleHash(), FallbackReason.VALIDATION_REQUIRED);
            return vanillaPlan(key, optimized, facts, FallbackReason.VALIDATION_REQUIRED);
        }

        SyntheticCoverageRunner.CoverageReport coverage = COVERAGE_RUNNER.report(optimized);
        SurfaceMetrics.coverage(coverage);
        SurfaceCertification certification = SurfaceCertification.from(coverage);
        if (coverage.status() == SyntheticCoverageRunner.CoverageStatus.PASSED && facts.directWriteCertified()) {
            SurfaceExecutionPlan directCandidate = new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT, optimized, facts, FallbackReason.UNCERTIFIED).withCertification(certification);
            GeneratedKernel directKernel = DIRECT_JIT.compile(directCandidate);
            if (directKernel != null) {
                SurfaceMetrics.tier0Certified();
                SurfaceMetrics.tier0DirectTemplatePlan();
                return new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT, optimized, facts, FallbackReason.UNCERTIFIED, certification, directKernel);
            }
            SurfaceMetrics.tier0CompileFailure();
        }

        GeneratedKernel directTemplateKernel = compileDirectTemplate(ruleSource, scan, facts);
        if (directTemplateKernel != null) {
            SurfaceMetrics.tier0Certified();
            SurfaceMetrics.tier0DirectTemplatePlan();
            return new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT,
                    optimized, facts, FallbackReason.UNCERTIFIED, certification, directTemplateKernel);
        }
        SurfaceMetrics.tier0Rejected();

        if (hybridEligible(scan, classification) && facts.safeForHybrid() && SurfaceCompilerConfig.ENABLE_TIER1_HYBRID) {
            SurfaceExecutionPlan hybridCandidate = new SurfaceExecutionPlan(key, SurfaceTier.GUARDED_HYBRID_JIT, SurfaceCommitMode.COW_SHADOW, optimized, facts, FallbackReason.UNCERTIFIED).withCertification(certification);
            GeneratedKernel hybridKernel = HYBRID_JIT.compile(hybridCandidate);
            if (hybridKernel != null) {
                SurfaceMetrics.tier1Compiled();
                return new SurfaceExecutionPlan(key, SurfaceTier.GUARDED_HYBRID_JIT, SurfaceCommitMode.COW_SHADOW, optimized, facts, FallbackReason.UNCERTIFIED, certification, hybridKernel);
            }
            SurfaceMetrics.tier1Rejected();
        } else {
            SurfaceMetrics.tier1Rejected();
        }

        SurfaceExecutionPlan tier2Candidate = new SurfaceExecutionPlan(key, SurfaceTier.MASK_INTERPRETER, SurfaceCommitMode.COW_SHADOW, optimized, facts, FallbackReason.UNCERTIFIED).withCertification(certification);
        boolean materialBackendReady = SurfaceCompilerConfig.ENABLE_TIER2_INTERPRETER && MASK_INTERPRETER.canExecute(tier2Candidate);
        if (materialBackendReady) {
            if (scan.vanillaOwned() && !scan.containsOpaqueCallouts()) {
                VectorSurfaceKernel vectorKernel = VectorSurfaceKernel.compile(ruleSource);
                if (vectorKernel != null) {
                    SurfaceMetrics.tier2VectorPlan();
                    return new SurfaceExecutionPlan(key, SurfaceTier.MASK_INTERPRETER, SurfaceCommitMode.COW_SHADOW,
                            optimized, facts, FallbackReason.UNCERTIFIED, certification, vectorKernel);
                }
                SurfaceMetrics.tier2VectorCompileFailure();
            }
            SurfaceMetrics.tier2GenericPlan();
            return tier2Candidate;
        }

        return vanillaPlan(key, optimized, facts, FallbackReason.UNCERTIFIED);
    }

    private static boolean hybridEligible(SurfaceRuleScanner.SurfaceScanResult scan, SurfaceRuleClassifier.Classification classification) {
        if (classification != null && classification.hybridEligible()) {
            return true;
        }
        return scan != null && scan.vanillaOwned() && !scan.containsOpaqueCallouts();
    }

    private static GeneratedKernel compileDirectTemplate(SurfaceRules.RuleSource ruleSource, SurfaceRuleScanner.SurfaceScanResult scan, SurfaceFacts facts) {
        if (!SurfaceCompilerConfig.ENABLE_TIER0_DIRECT
                || ruleSource == null
                || scan == null
                || facts == null
                || !facts.safeForInterpreter()
                || !scan.vanillaOwned()
                || scan.containsOpaqueCallouts()) {
            return null;
        }
        return DirectTemplateSurfaceKernel.compile(ruleSource);
    }

    private static boolean executeTier2(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        if (plan.hasKernel()) {
            SurfaceMetrics.tier2VectorExecution();
            return plan.kernel().execute(context);
        }
        SurfaceMetrics.tier2GenericExecution();
        return MASK_INTERPRETER.execute(plan, context);
    }

    private static boolean executeTier1(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        SurfaceMetrics.tier1HybridExecution();
        boolean executed = HYBRID_JIT.execute(plan.kernel(), context);
        if (!executed) {
            SurfaceMetrics.tier1HybridExecutionFailure();
        }
        return executed;
    }

    private static boolean executeTier0(SurfaceExecutionPlan plan, SurfaceExecutionContext context) {
        if (plan.commitMode() == SurfaceCommitMode.COW_SHADOW) {
            SurfaceMetrics.tier0VectorTemplateExecution();
        } else {
            SurfaceMetrics.tier0DirectTemplateExecution();
        }
        return DIRECT_JIT.execute(plan.kernel(), context);
    }

    private static SurfaceExecutionPlan vanillaPlan(FingerprintCacheKey key, SurfaceProgramIr ir, SurfaceFacts facts, FallbackReason reason) {
        return new SurfaceExecutionPlan(key, SurfaceTier.VANILLA_CLEAN_PATH, SurfaceCommitMode.VANILLA, ir, facts, reason);
    }

    private static String rootClassName(SurfaceRules.RuleSource ruleSource) {
        return ruleSource == null ? "null" : ruleSource.getClass().getName();
    }

    private static void openClassCircuitBreaker(String rootClassName, FallbackReason reason) {
        if (reason == FallbackReason.UNSAFE_RULE || reason == FallbackReason.DISABLED) {
            if (CLASS_CIRCUIT_BREAKERS.putIfAbsent(rootClassName, reason) == null) {
                SurfaceMetrics.classCircuitOpen();
            }
        }
    }

    private static SurfaceExecutionPlan identityGet(SurfaceRules.RuleSource ruleSource) {
        return ruleSource == null ? null : IDENTITY_PLAN_CACHE.getIfPresent(ruleSource);
    }

    private static void identityPut(SurfaceRules.RuleSource ruleSource, SurfaceExecutionPlan plan) {
        if (ruleSource != null && plan != null) {
            IDENTITY_PLAN_CACHE.put(ruleSource, plan);
        }
    }

    private static void identityRemove(SurfaceRules.RuleSource ruleSource) {
        if (ruleSource != null) {
            IDENTITY_PLAN_CACHE.invalidate(ruleSource);
        }
    }

    private static void recordPlan(SurfaceExecutionPlan plan) {
        if (plan == null || plan.key() == null) {
            return;
        }
        String fingerprint = plan.key().structuralRuleHash();
        TELEMETRY.tier(fingerprint, plan.tier());
        if (plan.fallbackReason() != null) {
            TELEMETRY.fallback(fingerprint, plan.fallbackReason());
        }
    }

    private static void recordExecution(SurfaceExecutionPlan plan, boolean success) {
        if (plan == null || plan.key() == null) {
            return;
        }
        TELEMETRY.execution(plan.key().structuralRuleHash(), plan.tier(), success);
    }

    private static void recordOpaqueNodes(SurfaceRuleScanner.SurfaceScanResult scan) {
        if (scan == null || scan.opaqueNodes().isEmpty()) {
            return;
        }
        for (SurfaceRuleScanner.OpaqueNode node : scan.opaqueNodes()) {
            TELEMETRY.opaqueNode(node.sourceClassName(), node.reason(), node.vanillaOwned(), node.condition());
        }
    }

    private static void recordAdapterScan(SurfaceRuleScanner.SurfaceScanResult scan, SurfaceRuleClassifier.Classification classification) {
        SurfaceAdapter adapter = ADAPTERS.find(scan.rootClassName()).orElse(null);
        if (adapter == null) {
            return;
        }
        boolean success = classification.compilerEligible();
        TELEMETRY.adapter(adapter.descriptor().id(), adapter.descriptor().safetyClass(), success, classification.vectorEligible());
    }
}
