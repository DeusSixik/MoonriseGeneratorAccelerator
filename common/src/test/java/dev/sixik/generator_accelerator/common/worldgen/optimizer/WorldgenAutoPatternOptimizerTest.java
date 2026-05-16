package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenEffectFlag;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenSafetyTier;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenUnitProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldgenAutoPatternOptimizerTest {
    @BeforeEach
    void resetMetrics() {
        WorldgenOptimizerMetrics.reset();
    }

    @Test
    void recognizesModdedOreLikeFeatureAsDataPlanWithStableGuards() {
        WorldgenUnitProfile profile = profile(
                "examplemod:rich_ore",
                "examplemod",
                "com.example.worldgen.RichOreFeature",
                Set.of(WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.READS_BLOCKS, WorldgenEffectFlag.USES_RANDOM),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                80
        );

        Optional<WorldgenGeneratedPlan> maybePlan = new WorldgenAutoPatternOptimizer().plan(profile);

        assertTrue(maybePlan.isPresent());
        WorldgenGeneratedPlan plan = maybePlan.get();
        assertTrue(plan.enabled());
        assertEquals(WorldgenOptimizationPattern.ORE_LIKE, plan.pattern());
        assertEquals(WorldgenFastPathKind.DATA_PLAN, plan.fastPathKind());
        assertEquals(WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES, plan.targetTier());
        assertTrue(plan.guards().stream().anyMatch(guard -> guard.name().equals("bytecodeHash")));
        assertEquals("examplemod", plan.attributes().get("namespace"));
    }

    @Test
    void refusesHardUnsafeRecognizedClass() {
        WorldgenUnitProfile unsafe = profile(
                "examplemod:async_ore",
                "examplemod",
                "com.example.worldgen.AsyncOreFeature",
                Set.of(WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.USES_THREADS),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                120
        );

        Optional<WorldgenGeneratedPlan> plan = new WorldgenAutoPatternOptimizer().plan(unsafe);

        assertTrue(plan.isEmpty());
        Map<String, Object> snapshot = WorldgenOptimizerMetrics.snapshot();
        assertEquals(1L, snapshot.get("fallbacks"));
        assertEquals(0L, snapshot.get("recognized"));
    }

    @Test
    void generatedJavaChosenForHotStreamPositionPipeline() {
        WorldgenUnitProfile profile = profile(
                "examplemod:blockpos_stream_patch",
                "examplemod",
                "com.example.worldgen.BlockPosStreamPlacementModifier",
                Set.of(WorldgenEffectFlag.STREAM_HEAVY, WorldgenEffectFlag.ALLOC_HEAVY, WorldgenEffectFlag.USES_RANDOM),
                WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE,
                256
        );

        WorldgenGeneratedPlan plan = new WorldgenPatternRecognizer().recognize(profile).orElseThrow();

        assertEquals(WorldgenOptimizationPattern.RANDOM_PATCH, plan.pattern());
        assertEquals(WorldgenFastPathKind.GENERATED_JAVA, plan.fastPathKind());
        assertEquals(WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE, plan.targetTier());
    }

    @Test
    void guardMismatchDeoptsExistingPlan() {
        WorldgenAutoPatternOptimizer optimizer = new WorldgenAutoPatternOptimizer();
        WorldgenUnitProfile original = profile(
                "examplemod:spring",
                "examplemod",
                "com.example.worldgen.SpringFeature",
                Set.of(WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.USES_RANDOM),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                20
        );
        WorldgenGeneratedPlan plan = optimizer.plan(original).orElseThrow();
        WorldgenUnitProfile changed = new WorldgenUnitProfile(
                original.id(), original.namespace(), original.className(), "differentHash", original.configHash(),
                original.registryEpoch(), original.entryPointMethod(), original.estimatedCost(), original.effectFlags(),
                original.safetyTier(), original.guards(), original.fallbackReason());

        WorldgenOptimizerDecision decision = optimizer.admit(plan, changed);

        assertEquals(WorldgenOptimizerAction.DEOPT, decision.action());
        assertEquals(WorldgenDeoptReason.GUARD_MISMATCH, decision.deoptReason());
        assertTrue(decision.reason().contains("bytecodeHash"));
    }

    @Test
    void parityMismatchDeoptsAndUpdatesSnapshot() {
        WorldgenAutoPatternOptimizer optimizer = new WorldgenAutoPatternOptimizer();
        WorldgenGeneratedPlan plan = optimizer.plan(profile(
                "examplemod:lake_disk",
                "examplemod",
                "com.example.worldgen.LakeDiskFeature",
                Set.of(WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.READS_HEIGHTMAP),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                44
        )).orElseThrow();

        WorldgenOptimizerDecision decision = optimizer.parity(plan, WorldgenParitySample.compare(plan.unitId(), 11L, 12L));

        assertEquals(WorldgenOptimizerAction.DEOPT, decision.action());
        assertEquals(WorldgenDeoptReason.PARITY_MISMATCH, decision.deoptReason());
        Map<String, Object> snapshot = WorldgenOptimizerMetrics.snapshot();
        assertEquals("worldgen-optimizer-v1", snapshot.get("schema"));
        assertEquals(1L, snapshot.get("parityMismatches"));
        assertEquals(1L, snapshot.get("deopts"));
    }

    @Test
    void pureLargeDensityUnitCanUseNativeVectorPlanWithoutMinecraftRuntime() {
        WorldgenUnitProfile profile = profile(
                "examplemod:custom_density",
                "examplemod",
                "com.example.worldgen.CustomDensityFunction",
                Set.of(WorldgenEffectFlag.PURE, WorldgenEffectFlag.READS_BIOMES),
                WorldgenSafetyTier.PURE_READ_ONLY,
                5000
        );

        WorldgenGeneratedPlan plan = new WorldgenPatternRecognizer().recognize(profile).orElseThrow();

        assertEquals(WorldgenOptimizationPattern.PURE_DENSITY_OR_SURFACE, plan.pattern());
        assertEquals(WorldgenFastPathKind.NATIVE_VECTOR, plan.fastPathKind());
        assertEquals(WorldgenSafetyTier.PURE_READ_ONLY, plan.targetTier());
    }

    private static WorldgenUnitProfile profile(
            String id,
            String namespace,
            String className,
            Set<WorldgenEffectFlag> flags,
            WorldgenSafetyTier tier,
            int cost
    ) {
        return new WorldgenUnitProfile(
                id,
                namespace,
                className,
                "hash-1",
                "config-1",
                42L,
                "place",
                cost,
                flags,
                tier,
                List.of("cheap-classifier"),
                ""
        );
    }
}
