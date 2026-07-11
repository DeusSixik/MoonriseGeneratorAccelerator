package dev.sixik.generator_accelerator.common.surface_compiler;

import dev.sixik.generator_accelerator.common.surface_compiler.cache.BoundedProgramStore;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.EpochClassLoader;
import dev.sixik.generator_accelerator.common.surface_compiler.cache.FingerprintCacheKey;
import dev.sixik.generator_accelerator.common.surface_compiler.callout.BorrowToken;
import dev.sixik.generator_accelerator.common.surface_compiler.callout.EscapeDetector;
import dev.sixik.generator_accelerator.common.surface_compiler.callout.SurfaceVectorInput;
import dev.sixik.generator_accelerator.common.surface_compiler.callout.SurfaceVectorOutput;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.interpreter.MaskInterpreterBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.DirectJitBackend;
import dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode.GeneratedKernel;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterDescriptor;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.CertifiedVectorSurfaceAdapter;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.SurfaceAdapter;
import dev.sixik.generator_accelerator.common.surface_compiler.facts.SurfaceFacts;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceRuleClassifier;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceRuleScanner;
import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceFingerprint;
import dev.sixik.generator_accelerator.common.surface_compiler.halo.HaloPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceStateToken;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.SurfaceOptimizer;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceCommitMode;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import dev.sixik.generator_accelerator.common.surface_compiler.semantic.MojangSurfaceHarness;
import dev.sixik.generator_accelerator.common.surface_compiler.semantic.VanillaInvocationContext;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.QuarantineManager;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.StateTraceValidator;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.SyntheticCoverageRunner;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.TranslationValidator;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.VanillaParityComparator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.TestVanillaLikeSequenceRuleSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCompilerParityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearRuntimeState() {
        SurfaceRuntime.clearCaches();
        SurfaceRuntime.adapters().clear();
        SurfaceRuntime.unsafeRules().clear();
        SurfaceMetrics.reset();
    }

    @Test
    void certifiedConstantStateRulesChooseDirectTierBeforeMutation() {
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(SurfaceRules.state(Blocks.STONE.defaultBlockState()));

        assertEquals(SurfaceTier.CERTIFIED_DIRECT_JIT, plan.tier());
        assertEquals(SurfaceCommitMode.DIRECT, plan.commitMode());
        assertEquals(FallbackReason.UNCERTIFIED, plan.fallbackReason());
        assertNotNull(plan.certification());
        assertEquals(SyntheticCoverageRunner.CoverageStatus.PASSED, plan.certification().coverageStatus());
        assertTrue(plan.certification().directTemplateCertified());
        assertTrue(plan.hasKernel());
        assertFalse(plan.useVanillaCleanPath());
        assertNotNull(plan.ir());
    }

    @Test
    void cachedPlanIsBoundedAndReusedForSameRuleSource() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.DIRT.defaultBlockState());

        SurfaceExecutionPlan first = SurfaceRuntime.prepare(rule);
        SurfaceExecutionPlan second = SurfaceRuntime.prepare(rule);

        assertSame(first, second);
        assertEquals(1, SurfaceCompilerCaches.store().size());
        assertEquals(1L, SurfaceMetrics.snapshot().get("cacheHits"));
    }

    @Test
    void structurallyEqualRuleSourcesShareFingerprint() {
        SurfaceRules.RuleSource first = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRules.RuleSource second = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRules.RuleSource different = SurfaceRules.state(Blocks.DIRT.defaultBlockState());

        assertEquals(SurfaceFingerprint.keyFor(first), SurfaceFingerprint.keyFor(second));
        assertNotEquals(SurfaceFingerprint.keyFor(first), SurfaceFingerprint.keyFor(different));
    }

    @Test
    void adapterRegistryChangesFingerprintCacheKey() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        FingerprintCacheKey base = SurfaceFingerprint.keyFor(rule);

        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(TestRuleSource.class.getName(),
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));

        assertNotEquals(base, SurfaceFingerprint.keyFor(rule));
    }

    @Test
    void vanillaVectorSafeShapesPromoteToTierZeroDirectTemplate() {
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0)), SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())),
                SurfaceRules.state(Blocks.STONE.defaultBlockState())));

        assertEquals(SurfaceTier.CERTIFIED_DIRECT_JIT, plan.tier());
        assertEquals(SurfaceCommitMode.DIRECT, plan.commitMode());
        assertFalse(plan.useVanillaCleanPath());
        assertTrue(plan.hasKernel());
        assertTrue(plan.kernel().getClass().getName().contains("DirectTemplateSurfaceKernel"));
        assertNotNull(plan.ir());
        assertTrue(plan.ir().tokenChainIsLinear());

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tier0Backend = (java.util.Map<String, Object>) SurfaceMetrics.snapshot().get("tier0Backend");
        assertEquals(1L, tier0Backend.get("directTemplatePlans"));
        assertEquals(0L, tier0Backend.get("vectorTemplatePlans"));
    }

    @Test
    void vanillaSafeTierZeroMissPromotesToTierOneBeforeTierTwo() {
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestVanillaLikeSequenceRuleSource());

        assertEquals(SurfaceTier.GUARDED_HYBRID_JIT, plan.tier());
        assertEquals(SurfaceCommitMode.COW_SHADOW, plan.commitMode());
        assertFalse(plan.useVanillaCleanPath());
        assertTrue(plan.hasKernel());
        assertTrue(plan.kernel().getClass().getName().contains("GeneratedHybridKernel"));
        assertNotNull(plan.ir());
        assertTrue(plan.facts().safeForHybrid());

        java.util.Map<String, Object> metrics = SurfaceMetrics.snapshot();
        assertEquals(0L, metrics.get("tier2Plans"));
        assertEquals(1L, metrics.get("tier1Compiled"));
        assertEquals(1L, metrics.get("tier0Rejected"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tier1Backend = (java.util.Map<String, Object>) metrics.get("tier1Backend");
        assertNotNull(tier1Backend);
        assertEquals(0L, tier1Backend.get("hybridExecutions"));
        assertEquals(0L, tier1Backend.get("hybridExecutionFailures"));
        assertEquals(0L, tier1Backend.get("specializedExecutions"));
        assertEquals(0L, tier1Backend.get("genericInterpreterExecutions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tierOneHybridExecutionMetricsAreVisible() {
        SurfaceMetrics.tier1HybridExecution();
        SurfaceMetrics.tier1HybridExecutionFailure();
        SurfaceMetrics.tier1SpecializedExecution();
        SurfaceMetrics.tier1GenericInterpreterExecution();

        java.util.Map<String, Object> tier1Backend = (java.util.Map<String, Object>) SurfaceMetrics.snapshot().get("tier1Backend");

        assertNotNull(tier1Backend);
        assertEquals(1L, tier1Backend.get("hybridExecutions"));
        assertEquals(1L, tier1Backend.get("hybridExecutionFailures"));
        assertEquals(1L, tier1Backend.get("specializedExecutions"));
        assertEquals(1L, tier1Backend.get("genericInterpreterExecutions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tierTwoMaskInterpreterAcceptsSafeCowPlansAndExposesMetrics() {
        SurfaceNode root = SurfaceNode.sequence(java.util.List.of(
                SurfaceNode.test(
                        SurfaceNode.condition(SurfaceNode.Kind.WATER_CHECK, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER,
                                "test.tier2", "water", java.util.List.of()),
                        SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test.tier2"),
                        "test.tier2"),
                SurfaceNode.state(Blocks.STONE.defaultBlockState(), "test.tier2")
        ), "test.tier2.mask");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.tier2.mask", root);
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        SurfaceStateToken t2 = t1.next();
        ir.add(new SurfaceOp("SEQUENCE", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.CONSTANT, t0, t1, "children=2"));
        ir.add(new SurfaceOp("WATER_CHECK", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t1, t2, "offset=-1"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:stone"));
        FingerprintCacheKey key = new FingerprintCacheKey("tier2-mask", "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
        SurfaceFacts facts = new SurfaceFacts(true, true, false, false, true, true, true, ir.ops().size(), 2,
                Set.of(SurfaceDomain.CONSTANT.name(), SurfaceDomain.WATER.name()), SnapshotPlan.none(), HaloPlan.none());
        SurfaceExecutionPlan plan = new SurfaceExecutionPlan(key, SurfaceTier.MASK_INTERPRETER, SurfaceCommitMode.COW_SHADOW,
                ir, facts, FallbackReason.UNCERTIFIED);

        MaskInterpreterBackend backend = new MaskInterpreterBackend();

        assertEquals(SurfaceTier.MASK_INTERPRETER, plan.tier());
        assertEquals(SurfaceCommitMode.COW_SHADOW, plan.commitMode());
        assertTrue(backend.supportsMaterialWrites(plan));
        assertTrue(backend.canExecute(plan));

        SurfaceMetrics.tierSelected(SurfaceTier.MASK_INTERPRETER);
        SurfaceMetrics.tier2GenericPlan();
        SurfaceMetrics.tier2GenericExecution();
        java.util.Map<String, Object> metrics = SurfaceMetrics.snapshot();
        java.util.Map<String, Object> tier2Backend = (java.util.Map<String, Object>) metrics.get("tier2Backend");

        assertEquals(1L, metrics.get("tier2Plans"));
        assertNotNull(tier2Backend);
        assertEquals(1L, tier2Backend.get("genericPlans"));
        assertEquals(1L, tier2Backend.get("genericExecutions"));
        assertEquals(0L, tier2Backend.get("vectorCompileFailures"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tierThreeVanillaCleanPathIsObservableAndNonExecutableByCompiler() {
        SurfaceProgramIr ir = new SurfaceProgramIr("test.tier3.vanilla");
        FingerprintCacheKey key = new FingerprintCacheKey("tier3-vanilla", "mc", "ga", 0L, "adapters", "runtime", "profile", "unsafe");
        SurfaceExecutionPlan plan = new SurfaceExecutionPlan(key, SurfaceTier.VANILLA_CLEAN_PATH, SurfaceCommitMode.VANILLA,
                ir, null, FallbackReason.DISABLED);

        assertTrue(plan.useVanillaCleanPath());
        assertFalse(plan.hasKernel());
        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, plan.tier());
        assertEquals(SurfaceCommitMode.VANILLA, plan.commitMode());

        SurfaceMetrics.tierSelected(SurfaceTier.VANILLA_CLEAN_PATH);
        java.util.Map<String, Object> metrics = SurfaceMetrics.snapshot();

        assertEquals(1L, metrics.get("vanillaPlans"));
        assertEquals(0L, metrics.get("tier0Plans"));
        assertEquals(0L, metrics.get("tier1Plans"));
        assertEquals(0L, metrics.get("tier2Plans"));
        assertNotNull((java.util.Map<String, Object>) metrics.get("tier2Backend"));
        assertNotNull((java.util.Map<String, Object>) metrics.get("tier1Backend"));
    }

    @Test
    void stateTraceValidatorRejectsBrokenTokenChain() {
        SurfaceProgramIr valid = new SurfaceProgramIr("test.valid");
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        SurfaceStateToken t2 = t1.next();
        valid.add(new SurfaceOp("A", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t0, t1, "a"));
        valid.add(new SurfaceOp("B", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.Y_BAND, t1, t2, "b"));

        SurfaceProgramIr broken = new SurfaceProgramIr("test.broken");
        broken.add(new SurfaceOp("A", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t0, t1, "a"));
        broken.add(new SurfaceOp("B", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.Y_BAND, t0, t2, "b"));

        StateTraceValidator validator = new StateTraceValidator();
        assertTrue(validator.validateTokenChain(valid));
        assertFalse(validator.validateTokenChain(broken));
    }

    @Test
    void optimizerCanRemoveDuplicatePureOpsWithoutInvalidatingStateTrace() {
        SurfaceProgramIr ir = new SurfaceProgramIr("test.pure-cse");
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:stone"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:stone"));
        ir.add(new SurfaceOp("WATER_CHECK", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t0, t1, "offset=-1"));

        SurfaceProgramIr optimized = new SurfaceOptimizer().optimize(ir);

        assertEquals(2, optimized.ops().size());
        assertTrue(new TranslationValidator().validate(ir, optimized));
    }

    @Test
    void optimizerRemovesOnlySafePureAndStableDuplicates() {
        SurfaceProgramIr ir = new SurfaceProgramIr("test.optimizer-safe");
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        ir.add(new SurfaceOp("Y_PREFILTER", SurfaceEffect.PURE, SurfaceDomain.Y_BAND, null, null, "0..64"));
        ir.add(new SurfaceOp("Y_PREFILTER", SurfaceEffect.PURE, SurfaceDomain.Y_BAND, null, null, "0..64"));
        ir.add(new SurfaceOp("Y_STABLE", SurfaceEffect.READ_ONLY_STABLE, SurfaceDomain.Y_BAND, null, null, "column"));
        ir.add(new SurfaceOp("Y_STABLE", SurfaceEffect.READ_ONLY_STABLE, SurfaceDomain.Y_BAND, null, null, "column"));
        ir.add(new SurfaceOp("WATER_CHECK", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t0, t1, "offset=-1"));

        SurfaceProgramIr optimized = new SurfaceOptimizer().optimize(ir);

        assertEquals(3, optimized.ops().size());
        assertTrue(new TranslationValidator().validate(ir, optimized));
        assertEquals(ir.ops().stream().filter(SurfaceOp::isStateful).toList(), optimized.ops().stream().filter(SurfaceOp::isStateful).toList());
    }

    @Test
    void optimizerDoesNotRemoveOrderedDuplicateCallouts() {
        SurfaceProgramIr ir = new SurfaceProgramIr("test.optimizer-ordered");
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        SurfaceStateToken t2 = t1.next();
        ir.add(new SurfaceOp("ADAPTER_CALLOUT", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.OPAQUE, t0, t1, "adapter=a"));
        ir.add(new SurfaceOp("ADAPTER_CALLOUT", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.OPAQUE, t1, t2, "adapter=a"));

        SurfaceProgramIr optimized = new SurfaceOptimizer().optimize(ir);

        assertEquals(2, optimized.ops().size());
        assertEquals(ir.ops(), optimized.ops());
        assertTrue(new TranslationValidator().validate(ir, optimized));
    }

    @Test
    void boundedStoreEvictsOldestPlans() {
        BoundedProgramStore store = new BoundedProgramStore(16);
        for (int i = 0; i < 32; i++) {
            FingerprintCacheKey key = new FingerprintCacheKey("rule-" + i, "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
            store.put(key, new SurfaceExecutionPlan(key, SurfaceTier.VANILLA_CLEAN_PATH, SurfaceCommitMode.VANILLA, null, null, FallbackReason.UNCERTIFIED));
        }

        assertEquals(16, store.size());
    }

    @Test
    void borrowTokenEscapeDetectorFlagsClosedBorrowedObject() {
        EscapeDetector detector = new EscapeDetector();
        BorrowToken token = new BorrowToken();
        Object borrowed = new Object();

        detector.borrow(borrowed, token);
        assertFalse(detector.escaped(borrowed));
        token.close();
        assertTrue(detector.escaped(borrowed));
    }

    @Test
    void quarantinedFingerprintAlwaysUsesVanillaCleanPath() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        SurfaceExecutionPlan first = SurfaceRuntime.prepare(rule);
        FingerprintCacheKey key = first.key();

        SurfaceRuntime.clearCaches();
        new QuarantineManager().quarantine(key, FallbackReason.EXECUTION_FAILURE);
        SurfaceExecutionPlan quarantined = SurfaceRuntime.prepare(rule);

        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, quarantined.tier());
        assertEquals(SurfaceCommitMode.VANILLA, quarantined.commitMode());
        assertEquals(FallbackReason.QUARANTINED, quarantined.fallbackReason());
    }

    @Test
    void primitiveReadOnlyAdapterMakesExternalRuleHybridEligible() {
        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(TestRuleSource.class.getName(),
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));
        SurfaceRuleScanner.SurfaceScanResult scan = new SurfaceRuleScanner().scan(new TestRuleSource());

        SurfaceRuleClassifier.Classification classification = new SurfaceRuleClassifier(
                SurfaceRuntime.adapters(),
                SurfaceRuntime.unsafeRules()
        ).classify(scan);

        assertTrue(classification.compilerEligible());
        assertTrue(classification.hybridEligible());
        assertEquals(FallbackReason.UNCERTIFIED, classification.fallbackReason());
    }

    @Test
    void primitiveReadOnlyAdapterPrepareSelectsTierOnePlan() {
        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(TestRuleSource.class.getName(),
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));

        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.GUARDED_HYBRID_JIT, plan.tier());
        assertEquals(SurfaceCommitMode.COW_SHADOW, plan.commitMode());
        assertTrue(plan.hasKernel());
        assertFalse(plan.useVanillaCleanPath());
        assertNotNull(plan.ir());
        assertTrue(plan.facts().safeForHybrid());
        assertTrue(plan.ir().tokenChainIsLinear());
        assertTrue(plan.kernel().getClass().getName().contains("GeneratedHybridKernel"));
    }

    @Test
    void optimizedNonVanillaPlansExposeExecutableKernelOnlyWhenRequired() {
        SurfaceExecutionPlan tier2 = SurfaceRuntime.prepare(SurfaceRules.state(Blocks.STONE.defaultBlockState()));

        assertEquals(SurfaceTier.CERTIFIED_DIRECT_JIT, tier2.tier());
        assertTrue(tier2.hasKernel());

        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(TestRuleSource.class.getName(),
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));
        SurfaceExecutionPlan tier1 = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.GUARDED_HYBRID_JIT, tier1.tier());
        assertTrue(tier1.hasKernel());
    }

    @Test
    void directBackendCompilesCertifiedConstantStateKernel() {
        SurfaceNode root = SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test.direct");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.direct", root);
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));
        FingerprintCacheKey key = new FingerprintCacheKey("direct", "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
        SurfaceFacts facts = new SurfaceFacts(true, true, true, false, false, false, false, 1, 0,
                Set.of(SurfaceDomain.CONSTANT.name()), SnapshotPlan.none(), HaloPlan.none());
        SurfaceExecutionPlan plan = new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT,
                ir, facts, FallbackReason.UNCERTIFIED);

        GeneratedKernel kernel = new DirectJitBackend().compile(plan);

        assertNotNull(kernel);
    }

    @Test
    void syntheticCoverageCertifiesOnlyDirectTemplatesWithoutSamples() {
        SurfaceNode root = SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test.coverage.direct");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.coverage.direct", root);
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));

        assertEquals(SyntheticCoverageRunner.CoverageStatus.PASSED, new SyntheticCoverageRunner().run(ir));
        assertTrue(new SyntheticCoverageRunner().report(ir).directTemplate());
    }

    @Test
    void syntheticCoverageMatrixRequiresBranchesActionsAndTraceForGenericCertification() {
        SurfaceNode root = SurfaceNode.sequence(java.util.List.of(
                SurfaceNode.test(
                        SurfaceNode.condition(SurfaceNode.Kind.WATER_CHECK, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, "test", "water", java.util.List.of()),
                        SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test"),
                        "test")
        ), "test.coverage.matrix");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.coverage.matrix", root);
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        ir.add(new SurfaceOp("SEQUENCE", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.CONSTANT, t0, t1, "children=1"));
        ir.add(new SurfaceOp("IF_TRUE", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.OPAQUE, t1, t1.next(), "test"));
        ir.add(new SurfaceOp("WATER_CHECK", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t1.next(), t1.next().next(), "water"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));

        SyntheticCoverageRunner.CoverageMatrix matrix = new SyntheticCoverageRunner().matrix(ir);

        assertEquals(3, matrix.statefulOps());
        assertTrue(matrix.hasBranchCoverage());
        assertTrue(matrix.hasMaterialActionCoverage());
        assertTrue(matrix.hasStateTraceCoverage());
    }

    @Test
    void vanillaBandlandsScansAsCoveredStatefulCalloutNotOpaqueFallback() {
        SurfaceRuleScanner.SurfaceScanResult scan = new SurfaceRuleScanner().scan(SurfaceRules.Bandlands.INSTANCE);

        assertFalse(scan.containsOpaqueCallouts());
        assertEquals(0, scan.opaqueCallouts());
        assertTrue(scan.opaqueNodes().isEmpty());
        assertEquals(SurfaceNode.Kind.BANDLANDS, scan.root().kind());
        assertEquals(SurfaceEffect.READ_ONLY_ORDERED, scan.root().effect());
        assertEquals(SurfaceDomain.OPAQUE, scan.root().domain());
        assertTrue(scan.statefulNodes() > 0);
    }

    @Test
    void runtimeTelemetryRecordsOpaqueExternalRuleBlockers() {
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, plan.tier());
        assertTrue(SurfaceRuntime.telemetry().opaqueNodes().containsKey("rule|external|"
                + TestRuleSource.class.getName() + "|external rule source"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageMetricsExposeRejectionReasons() {
        SurfaceRuntime.prepare(SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0)), SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())),
                SurfaceRules.state(Blocks.STONE.defaultBlockState())));

        java.util.Map<String, Object> coverage = (java.util.Map<String, Object>) SurfaceMetrics.snapshot().get("coverage");
        java.util.Map<String, Object> rejectionReasons = (java.util.Map<String, Object>) coverage.get("rejectionReasons");

        assertNotNull(rejectionReasons);
        assertTrue((long) rejectionReasons.get("tooFewSyntheticSamples") >= 1L);
    }

    @Test
    void vanillaParityComparatorRejectsChangedCandidateOps() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRuleScanner.SurfaceScanResult scan = new SurfaceRuleScanner().scan(rule);
        SurfaceProgramIr candidate = new SurfaceProgramIr(scan.rootClassName(), scan.root());
        candidate.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:dirt"));

        assertFalse(new VanillaParityComparator().equivalent(rule, candidate));
    }

    @Test
    void directBackendRejectsConditionalSequenceTemplate() {
        SurfaceNode root = SurfaceNode.sequence(java.util.List.of(
                SurfaceNode.test(
                        SurfaceNode.condition(SurfaceNode.Kind.WATER_CHECK, SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, "test", "water", java.util.List.of()),
                        SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test"),
                        "test"),
                SurfaceNode.state(Blocks.STONE.defaultBlockState(), "test")
        ), "test.direct.sequence");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.direct.sequence", root);
        SurfaceStateToken t0 = SurfaceStateToken.initial();
        SurfaceStateToken t1 = t0.next();
        ir.add(new SurfaceOp("SEQUENCE", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.CONSTANT, t0, t1, "children=2"));
        ir.add(new SurfaceOp("WATER_CHECK", SurfaceEffect.READ_ONLY_ORDERED, SurfaceDomain.WATER, t1, t1.next(), "water"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:stone"));
        FingerprintCacheKey key = new FingerprintCacheKey("conditional", "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
        SurfaceFacts facts = new SurfaceFacts(true, true, false, false, true, true, true, ir.ops().size(), 2,
                Set.of(SurfaceDomain.CONSTANT.name(), SurfaceDomain.WATER.name()), SnapshotPlan.none(), HaloPlan.none());
        SurfaceExecutionPlan plan = new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT,
                ir, facts, FallbackReason.UNCERTIFIED);

        assertFalse(new DirectJitBackend().canCompile(plan));
    }

    @Test
    void directBackendRejectsSyntheticSequenceEvenWhenFactsAreMarkedDirect() {
        SurfaceNode root = SurfaceNode.sequence(java.util.List.of(
                SurfaceNode.state(Blocks.GRASS_BLOCK.defaultBlockState(), "test"),
                SurfaceNode.state(Blocks.STONE.defaultBlockState(), "test")
        ), "test.direct.sequence.pure");
        SurfaceProgramIr ir = new SurfaceProgramIr("test.direct.sequence.pure", root);
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:grass_block"));
        ir.add(new SurfaceOp("STATE", SurfaceEffect.PURE, SurfaceDomain.CONSTANT, null, null, "minecraft:stone"));
        FingerprintCacheKey key = new FingerprintCacheKey("synthetic-sequence", "mc", "ga", 0L, "adapters", "runtime", "profile", "safe");
        SurfaceFacts facts = new SurfaceFacts(true, true, true, false, false, false, false, ir.ops().size(), 0,
                Set.of(SurfaceDomain.CONSTANT.name()), SnapshotPlan.none(), HaloPlan.none());
        SurfaceExecutionPlan plan = new SurfaceExecutionPlan(key, SurfaceTier.CERTIFIED_DIRECT_JIT, SurfaceCommitMode.DIRECT,
                ir, facts, FallbackReason.UNCERTIFIED);

        assertFalse(new DirectJitBackend().canCompile(plan));
    }

    @Test
    void runtimeTelemetryRecordsTierAndFallbackByFingerprint() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(rule);
        String fingerprint = plan.key().structuralRuleHash();

        assertEquals(1L, SurfaceRuntime.telemetry().snapshot().get("tier." + plan.tier() + "." + fingerprint));
        assertEquals(1L, SurfaceRuntime.telemetry().snapshot().get("fallback." + plan.fallbackReason() + "." + fingerprint));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageMetricsExposeCertificationBlockers() {
        SurfaceRuntime.prepare(SurfaceRules.state(Blocks.DIRT.defaultBlockState()));
        SurfaceRuntime.prepare(SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0)), SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())),
                SurfaceRules.state(Blocks.STONE.defaultBlockState())));

        java.util.Map<String, Object> coverage = (java.util.Map<String, Object>) SurfaceMetrics.snapshot().get("coverage");

        assertNotNull(coverage);
        assertEquals(2L, coverage.get("runs"));
        assertEquals(1L, coverage.get("directTemplates"));
        assertTrue((long) coverage.get("passed") >= 1L);
        assertTrue((double) coverage.get("avgDomains") > 0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void surfaceMetricsExposeLatencyAndCoverageDiagnostics() {
        SurfaceMetrics.setEnabled(true);
        long start = SurfaceMetrics.startTimer();
        SurfaceMetrics.optimizedExecution(start);

        java.util.Map<String, Object> snapshot = SurfaceMetrics.snapshot();
        java.util.Map<String, Object> latency = (java.util.Map<String, Object>) snapshot.get("latency");
        java.util.Map<String, Object> coverage = (java.util.Map<String, Object>) snapshot.get("coverage");

        assertNotNull(latency);
        assertEquals(1L, latency.get("optimizedExecutions"));
        assertNotNull(coverage);
        SurfaceMetrics.setEnabled(Boolean.getBoolean("ga.surface.metrics"));
    }

    @Test
    void mojangOracleHarnessReportsMissingContextInsteadOfPretendingReady() {
        MojangSurfaceHarness.HarnessStatus status = new MojangSurfaceHarness().status(null);

        assertFalse(status.ready());
        assertEquals("missing_context", status.reason());
    }

    @Test
    void mojangOracleHarnessComparesCandidateDebugIrWhenInvocationContextIsComplete() {
        SurfaceRules.RuleSource rule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRuleScanner.SurfaceScanResult scan = new SurfaceRuleScanner().scan(rule);
        SurfaceProgramIr candidate = new dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceIrBuilder().build(scan);
        VanillaInvocationContext context = new VanillaInvocationContext(
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                rule
        );

        MojangSurfaceHarness.HarnessComparison comparison = new MojangSurfaceHarness().compare(context, candidate);

        assertTrue(comparison.comparable());
        assertTrue(comparison.matched());
        assertEquals("debug_ir", comparison.mode());
    }

    @Test
    void certifiedVectorAdapterIsHybridAndTelemetryVectorEligible() {
        String className = TestRuleSource.class.getName();
        SurfaceRuntime.adapters().register(new TestVectorAdapter(className, 8));

        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.GUARDED_HYBRID_JIT, plan.tier());
        assertTrue(plan.hasKernel());
        assertTrue(SurfaceRuntime.telemetry().adapterStats().get(className).vectorEligible());
        assertEquals(0L, SurfaceRuntime.telemetry().adapterStats().get(className).vectorFailures());
    }

    @Test
    void vectorAdapterRequiresCertifiedAbiAndMatchingWidth() {
        String className = TestRuleSource.class.getName();
        SurfaceRuntime.adapters().register(new TestBrokenVectorAdapter(className));

        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, plan.tier());
        assertEquals(1L, SurfaceRuntime.telemetry().adapterStats().get(className).failures());
        assertFalse(SurfaceRuntime.telemetry().adapterStats().get(className).vectorEligible());
    }

    @Test
    void runtimeClearRetiresGeneratedKernelEpochs() {
        SurfaceRuntime.prepare(SurfaceRules.state(Blocks.DIRT.defaultBlockState()));
        assertTrue(EpochClassLoader.liveLoaderCount() > 0);

        SurfaceRuntime.clearCaches();

        assertEquals(0, EpochClassLoader.liveLoaderCount());
    }

    @Test
    void generatedKernelsShareOneLoaderPerEpochAndRotateAfterRetireAll() {
        SurfaceRuntime.prepare(SurfaceRules.state(Blocks.DIRT.defaultBlockState()));
        EpochClassLoader firstLoader = EpochClassLoader.create(SurfaceCompilerParityTest.class.getClassLoader());
        long firstEpoch = firstLoader.epoch();

        SurfaceRuntime.prepare(SurfaceRules.state(Blocks.STONE.defaultBlockState()));
        EpochClassLoader sameEpochLoader = EpochClassLoader.create(SurfaceCompilerParityTest.class.getClassLoader());

        assertSame(firstLoader, sameEpochLoader);
        assertEquals(1, EpochClassLoader.liveLoaderCount());
        assertEquals(firstEpoch, sameEpochLoader.epoch());

        EpochClassLoader.retireAll();
        EpochClassLoader secondLoader = EpochClassLoader.create(SurfaceCompilerParityTest.class.getClassLoader());

        assertNotSame(firstLoader, secondLoader);
        assertNotEquals(firstEpoch, secondLoader.epoch());
        assertEquals(1, EpochClassLoader.liveLoaderCount());
    }

    @Test
    void knownUnsafeRuleOverridesAdapterEligibility() {
        String className = TestRuleSource.class.getName();
        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(className,
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));
        SurfaceRuntime.unsafeRules().add(className);
        SurfaceRuleScanner.SurfaceScanResult scan = new SurfaceRuleScanner().scan(new TestRuleSource());

        SurfaceRuleClassifier.Classification classification = new SurfaceRuleClassifier(
                SurfaceRuntime.adapters(),
                SurfaceRuntime.unsafeRules()
        ).classify(scan);

        assertFalse(classification.compilerEligible());
        assertFalse(classification.hybridEligible());
        assertEquals(FallbackReason.UNSAFE_RULE, classification.fallbackReason());
    }

    @Test
    void adapterTelemetryAndFallbackReporterRecordUnsafeAdapterFallback() {
        String className = TestRuleSource.class.getName();
        SurfaceRuntime.adapters().register(new TestSurfaceAdapter(className,
                AdapterSafetyClass.READ_ONLY_COMPILER_ITERATED_SCALAR, true));
        SurfaceRuntime.unsafeRules().add(className);

        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(new TestRuleSource());

        assertEquals(SurfaceTier.VANILLA_CLEAN_PATH, plan.tier());
        assertEquals(1L, SurfaceRuntime.telemetry().adapterStats().get(className).calls());
        assertEquals(1L, SurfaceRuntime.telemetry().adapterStats().get(className).failures());
        assertTrue(SurfaceRuntime.fallbackReporter().events().stream().anyMatch(event -> event.endsWith(":" + FallbackReason.UNSAFE_RULE)));
    }

    private record TestSurfaceAdapter(String ownerClass, AdapterSafetyClass safetyClass, boolean primitiveAbi) implements SurfaceAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(this.ownerClass, this.ownerClass, this.safetyClass, "test", this.primitiveAbi);
        }
    }

    private record TestVectorAdapter(String ownerClass, int width) implements CertifiedVectorSurfaceAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(this.ownerClass, this.ownerClass, AdapterSafetyClass.READ_ONLY_CERTIFIED_VECTOR,
                    "test-vector", true, true, this.width, "junit-vector-cert");
        }

        @Override
        public int vectorWidth() {
            return this.width;
        }

        @Override
        public void evaluateVector(SurfaceVectorInput input, SurfaceVectorOutput output) {
            for (int i = 0; i < input.length(); i++) {
                output.set(i, Blocks.STONE.defaultBlockState());
            }
        }
    }

    private record TestBrokenVectorAdapter(String ownerClass) implements CertifiedVectorSurfaceAdapter {
        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor(this.ownerClass, this.ownerClass, AdapterSafetyClass.READ_ONLY_CERTIFIED_VECTOR,
                    "test-vector-broken", true, true, 8, "junit-vector-cert");
        }

        @Override
        public int vectorWidth() {
            return 16;
        }

        @Override
        public void evaluateVector(SurfaceVectorInput input, SurfaceVectorOutput output) {
            throw new AssertionError("broken test adapter must never execute");
        }
    }

    private static final class TestRuleSource implements SurfaceRules.RuleSource {
        @Override
        public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
            return (x, y, z) -> null;
        }

        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
            return SurfaceRules.state(Blocks.AIR.defaultBlockState()).codec();
        }
    }
}
