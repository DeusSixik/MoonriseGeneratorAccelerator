package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.exceptions.UnknownSurfaceConditionSource;
import dev.sixik.generator_accelerator.common.surface.exceptions.UnknownSurfaceRuleSource;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

public final class SurfaceRuleCompiler {
    private static final boolean LITHOSTITCHED_LOADED = isLoaded("dev.worldgen.lithostitched.Lithostitched");
    private static final boolean TERRABLENDER_LOADED = isLoaded("terrablender.core.TerraBlender");
    private static final boolean BIOMES_WEVE_GONE_LOADED = isLoaded("net.potionstudios.biomeswevegone.BiomesWeveGone");

    private SurfaceRuleCompiler() {
    }

    public static SurfaceProgram compile(SurfaceRules.RuleSource ruleSource) {
        long compileStart = SurfaceMetrics.startTimer();
        try {
            if (SurfaceCompilerConfig.IR) {
                try {
                    return compileWithIr(ruleSource);
                } catch (RuntimeException ignored) {
                    SurfaceMetrics.irFallback();
                }
            }
            return compileLegacy(ruleSource);
        } finally {
            SurfaceMetrics.recordCompileTime(compileStart);
        }
    }

    private static SurfaceProgram compileWithIr(SurfaceRules.RuleSource ruleSource) {
        SurfaceIRCompileResult result = SurfaceIRCompiler.compile(ruleSource);
        SurfaceMetrics.compiledProgram();
        SurfaceMetrics.irProgram();
        SurfaceMetrics.interpretedProgram();
        if (SurfaceMetrics.enabled()) {
            SurfaceMetrics.irFallbackNodes(result.fallbackRuleCount(), result.fallbackConditionCount());
        }
        SurfacePlanDump.compiledIr(
                ruleSource,
                result.ir(),
                result.program(),
                result.rootNodeName(),
                result.compiledConditionCount(),
                result.conditionCacheSlots()
        );
        return result.program();
    }

    private static SurfaceProgram compileLegacy(SurfaceRules.RuleSource ruleSource) {
        Builder builder = new Builder();
        builder.countRuleConditions(ruleSource);
        SurfaceRuleNode root = builder.compileRule(ruleSource);
        SurfaceProgram program = new SurfaceProgram(root, builder.fallbackIslandCount);
        SurfaceProgram optimized = SurfacePlanOptimizer.optimize(program);
        SurfaceMetrics.compiledProgram();
        SurfaceMetrics.interpretedProgram();
        SurfacePlanDump.compiled(
                ruleSource,
                optimized,
                root.getClass().getName(),
                builder.compiledConditions.size(),
                builder.structuralCompiledConditions.size(),
                builder.nextConditionCacheSlot,
                builder.fallbackIslandCount,
                builder.fallbackRuleClasses,
                builder.fallbackConditionClasses
        );
        return optimized;
    }

    static SurfaceProgram compileLegacyDirect(SurfaceRules.RuleSource ruleSource) {
        return compileLegacy(ruleSource);
    }

    static SurfaceRuleNode compileLegacyRuleNode(SurfaceRules.RuleSource ruleSource) {
        Builder builder = new Builder();
        builder.countRuleConditions(ruleSource);
        return builder.compileRule(ruleSource);
    }

    static SurfaceConditionNode compileLegacyConditionNode(SurfaceRules.ConditionSource conditionSource) {
        Builder builder = new Builder();
        builder.countCondition(conditionSource);
        return builder.compileCondition(conditionSource);
    }

    private static boolean isLoaded(String className) {
        try {
            Class.forName(className, false, SurfaceRuleCompiler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    private static final class Builder implements SurfaceCompilerContext {
        private final IdentityHashMap<SurfaceRules.ConditionSource, Integer> conditionUseCounts = new IdentityHashMap<>();
        private final IdentityHashMap<SurfaceRules.ConditionSource, SurfaceConditionNode> compiledConditions = new IdentityHashMap<>();
        private final HashMap<ConditionKey, Integer> structuralConditionUseCounts = new HashMap<>();
        private final HashMap<ConditionKey, SurfaceConditionNode> structuralCompiledConditions = new HashMap<>();
        private final List<String> fallbackRuleClasses = SurfaceCompilerConfig.DUMP ? new ArrayList<>() : List.of();
        private final List<String> fallbackConditionClasses = SurfaceCompilerConfig.DUMP ? new ArrayList<>() : List.of();
        private int nextConditionCacheSlot;
        private int fallbackIslandCount;

        private void countRuleConditions(SurfaceRules.RuleSource ruleSource) {
            if (ruleSource instanceof SurfaceRules.TestRuleSource testRule) {
                countCondition(testRule.ifTrue());
                countRuleConditions(testRule.thenRun());
                return;
            }

            if (ruleSource instanceof SurfaceRules.SequenceRuleSource sequenceRule) {
                for (SurfaceRules.RuleSource child : sequenceRule.sequence()) {
                    countRuleConditions(child);
                }
            }
        }

        private void countCondition(SurfaceRules.ConditionSource conditionSource) {
            this.conditionUseCounts.put(conditionSource, this.conditionUseCounts.getOrDefault(conditionSource, 0) + 1);
            ConditionKey key = conditionKey(conditionSource);
            if (key != null) {
                this.structuralConditionUseCounts.put(key, this.structuralConditionUseCounts.getOrDefault(key, 0) + 1);
            }

            if (conditionSource instanceof SurfaceRules.NotConditionSource notCondition) {
                countCondition(notCondition.target());
            }
        }

        @Override
        public SurfaceRuleNode compileRule(SurfaceRules.RuleSource ruleSource) {
            if (ruleSource instanceof SurfaceRules.BlockRuleSource blockRule) {
                return new BlockSurfaceRuleNode(blockRule.resultState());
            }

            if (ruleSource instanceof SurfaceRules.TestRuleSource testRule) {
                return compileTestRule(testRule);
            }

            if (ruleSource instanceof SurfaceRules.SequenceRuleSource sequenceRule) {
                List<SurfaceRuleNode> flattened = new ArrayList<>();
                flattenSequence(sequenceRule, flattened);
                if (flattened.isEmpty()) {
                    return EmptySurfaceRuleNode.INSTANCE;
                }
                if (flattened.size() == 1) {
                    return flattened.get(0);
                }
                return new SequenceSurfaceRuleNode(flattened.toArray(new SurfaceRuleNode[0]));
            }

            if (LITHOSTITCHED_LOADED) {
                SurfaceRuleNode compiled = LithostitchedSurfaceCompilerData.compileRule(ruleSource, this);
                if (compiled != null) return compiled;
            }

            if (TERRABLENDER_LOADED) {
                SurfaceRuleNode compiled = TerrablenderSurfaceCompilerData.compileRule(ruleSource, this);
                if (compiled != null) return compiled;
            }

            if (BIOMES_WEVE_GONE_LOADED) {
                SurfaceRuleNode compiled = BiomesWeveGoneSurfaceCompilerData.compileRule(ruleSource, this);
                if (compiled != null) return compiled;
            }

            try {
                this.fallbackIslandCount++;
                SurfaceMetrics.fallbackRule(ruleSource.getClass());
                if (SurfaceCompilerConfig.DUMP) {
                    this.fallbackRuleClasses.add(ruleSource.getClass().getName());
                }
                return new VectorSurfaceRuleBridgeNode(VectorRuleCompiler.compileRule(ruleSource));
            } catch (RuntimeException e) {
                throw new UnknownSurfaceRuleSource("Unknown surface rule source: " + ruleSource.getClass().getName(), e);
            }
        }

        private SurfaceRuleNode compileTestRule(SurfaceRules.TestRuleSource testRule) {
            List<SurfaceConditionNode> conditions = new ArrayList<>(2);
            SurfaceRules.RuleSource finalRule = testRule;

            while (finalRule instanceof SurfaceRules.TestRuleSource currentTest) {
                conditions.add(compileCondition(currentTest.ifTrue()));
                finalRule = currentTest.thenRun();
            }

            SurfaceConditionNode combinedCondition = combineAll(conditions);
            if (finalRule instanceof SurfaceRules.BlockRuleSource blockRule) {
                return new TestBlockSurfaceRuleNode(combinedCondition, Block.getId(blockRule.resultState()));
            }

            return new TestSurfaceRuleNode(combinedCondition, compileRule(finalRule));
        }

        private SurfaceConditionNode combineAll(List<SurfaceConditionNode> conditions) {
            if (conditions.size() == 1) {
                return conditions.get(0);
            }
            return new AllOfSurfaceConditionNode(conditions.toArray(new SurfaceConditionNode[0]));
        }

        private void flattenSequence(SurfaceRules.SequenceRuleSource sequenceRule, List<SurfaceRuleNode> target) {
            for (SurfaceRules.RuleSource child : sequenceRule.sequence()) {
                if (child instanceof SurfaceRules.SequenceRuleSource childSequence) {
                    flattenSequence(childSequence, target);
                } else {
                    target.add(compileRule(child));
                }
            }
        }

        @Override
        public SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource) {
            SurfaceConditionNode existing = this.compiledConditions.get(conditionSource);
            if (existing != null) {
                return existing;
            }

            ConditionKey key = conditionKey(conditionSource);
            if (key != null) {
                SurfaceConditionNode structuralExisting = this.structuralCompiledConditions.get(key);
                if (structuralExisting != null) {
                    this.compiledConditions.put(conditionSource, structuralExisting);
                    return structuralExisting;
                }
            }

            SurfaceConditionNode compiled = compileConditionUncached(conditionSource);
            int identityUses = this.conditionUseCounts.getOrDefault(conditionSource, 0);
            int structuralUses = key == null ? 0 : this.structuralConditionUseCounts.getOrDefault(key, 0);
            if (identityUses > 1 || structuralUses > 1) {
                compiled = new CachedSurfaceConditionNode(compiled, this.nextConditionCacheSlot++);
            }
            if (SurfaceMetrics.enabled()) {
                String metricKind = compiled.getClass().getSimpleName();
                compiled = new TimedSurfaceConditionNode(compiled, metricKind);
            }

            this.compiledConditions.put(conditionSource, compiled);
            if (key != null) {
                this.structuralCompiledConditions.put(key, compiled);
            }
            return compiled;
        }

        private SurfaceConditionNode compileConditionUncached(SurfaceRules.ConditionSource conditionSource) {
            if (conditionSource instanceof SurfaceRules.BiomeConditionSource biomeCondition) {
                return new BiomeSurfaceConditionNode(biomeCondition.biomes);
            }

            if (conditionSource instanceof SurfaceRules.StoneDepthCheck stoneDepthCheck) {
                return new StoneDepthSurfaceConditionNode(
                        stoneDepthCheck.offset(),
                        stoneDepthCheck.addSurfaceDepth(),
                        stoneDepthCheck.secondaryDepthRange(),
                        stoneDepthCheck.surfaceType()
                );
            }

            if (conditionSource instanceof SurfaceRules.YConditionSource yCondition) {
                return new YSurfaceConditionNode(
                        yCondition.anchor(),
                        yCondition.surfaceDepthMultiplier(),
                        yCondition.addStoneDepth()
                );
            }

            if (conditionSource instanceof SurfaceRules.NotConditionSource notCondition) {
                return new NotSurfaceConditionNode(compileCondition(notCondition.target()));
            }

            if (conditionSource instanceof SurfaceRules.NoiseThresholdConditionSource noiseThresholdCondition) {
                return new NoiseThresholdSurfaceConditionNode(
                        noiseThresholdCondition.noise(),
                        noiseThresholdCondition.minThreshold(),
                        noiseThresholdCondition.maxThreshold()
                );
            }

            if (conditionSource instanceof SurfaceRules.VerticalGradientConditionSource verticalGradientCondition) {
                return new VerticalGradientSurfaceConditionNode(
                        verticalGradientCondition.trueAtAndBelow(),
                        verticalGradientCondition.falseAtAndAbove(),
                        verticalGradientCondition.randomName()
                );
            }

            if (conditionSource == SurfaceRules.AbovePreliminarySurface.INSTANCE) {
                return AbovePreliminarySurfaceConditionNode.INSTANCE;
            }

            if (conditionSource instanceof SurfaceRules.WaterConditionSource waterCondition) {
                return new WaterSurfaceConditionNode(
                        waterCondition.offset(),
                        waterCondition.surfaceDepthMultiplier(),
                        waterCondition.addStoneDepth()
                );
            }

            if (conditionSource == SurfaceRules.Temperature.INSTANCE) {
                return TemperatureSurfaceConditionNode.INSTANCE;
            }

            if (conditionSource == SurfaceRules.Steep.INSTANCE) {
                return SteepSurfaceConditionNode.INSTANCE;
            }

            if (conditionSource == SurfaceRules.Hole.INSTANCE) {
                return HoleSurfaceConditionNode.INSTANCE;
            }

            if (LITHOSTITCHED_LOADED) {
                SurfaceConditionNode compiled = LithostitchedSurfaceCompilerData.compileCondition(conditionSource, this);
                if (compiled != null) return compiled;
            }

            if (TERRABLENDER_LOADED) {
                SurfaceConditionNode compiled = TerrablenderSurfaceCompilerData.compileCondition(conditionSource, this);
                if (compiled != null) return compiled;
            }

            if (BIOMES_WEVE_GONE_LOADED) {
                SurfaceConditionNode compiled = BiomesWeveGoneSurfaceCompilerData.compileCondition(conditionSource, this);
                if (compiled != null) return compiled;
            }

            try {
                SurfaceMetrics.fallbackCondition(conditionSource.getClass());
                if (SurfaceCompilerConfig.DUMP) {
                    this.fallbackConditionClasses.add(conditionSource.getClass().getName());
                }
                return new VectorSurfaceConditionBridgeNode(VectorRuleCompiler.compileCondition(conditionSource));
            } catch (RuntimeException e) {
                throw new UnknownSurfaceConditionSource("Unknown surface condition source: " + conditionSource.getClass().getName(), e);
            }
        }

        private ConditionKey conditionKey(SurfaceRules.ConditionSource conditionSource) {
            if (conditionSource instanceof SurfaceRules.BiomeConditionSource biomeCondition) {
                return new ConditionKey("biome", List.copyOf(biomeCondition.biomes));
            }
            if (conditionSource instanceof SurfaceRules.StoneDepthCheck stoneDepthCheck) {
                return new ConditionKey("stone_depth", stoneDepthCheck.offset(), stoneDepthCheck.addSurfaceDepth(), stoneDepthCheck.secondaryDepthRange(), stoneDepthCheck.surfaceType());
            }
            if (conditionSource instanceof SurfaceRules.YConditionSource yCondition) {
                return new ConditionKey("y", yCondition.anchor(), yCondition.surfaceDepthMultiplier(), yCondition.addStoneDepth());
            }
            if (conditionSource instanceof SurfaceRules.NotConditionSource notCondition) {
                ConditionKey target = conditionKey(notCondition.target());
                return target == null ? null : new ConditionKey("not", target);
            }
            if (conditionSource instanceof SurfaceRules.NoiseThresholdConditionSource noiseThresholdCondition) {
                return new ConditionKey("noise", noiseThresholdCondition.noise(), noiseThresholdCondition.minThreshold(), noiseThresholdCondition.maxThreshold());
            }
            if (conditionSource instanceof SurfaceRules.VerticalGradientConditionSource verticalGradientCondition) {
                return new ConditionKey("vertical_gradient", verticalGradientCondition.trueAtAndBelow(), verticalGradientCondition.falseAtAndAbove(), verticalGradientCondition.randomName());
            }
            if (conditionSource == SurfaceRules.AbovePreliminarySurface.INSTANCE) {
                return new ConditionKey("above_preliminary");
            }
            if (conditionSource instanceof SurfaceRules.WaterConditionSource waterCondition) {
                return new ConditionKey("water", waterCondition.offset(), waterCondition.surfaceDepthMultiplier(), waterCondition.addStoneDepth());
            }
            if (conditionSource == SurfaceRules.Temperature.INSTANCE) {
                return new ConditionKey("temperature");
            }
            if (conditionSource == SurfaceRules.Steep.INSTANCE) {
                return new ConditionKey("steep");
            }
            if (conditionSource == SurfaceRules.Hole.INSTANCE) {
                return new ConditionKey("hole");
            }
            return null;
        }
    }

    private static final class ConditionKey {
        private final String kind;
        private final Object[] values;
        private final int hash;

        private ConditionKey(String kind, Object... values) {
            this.kind = kind;
            this.values = values;
            this.hash = 31 * kind.hashCode() + Arrays.hashCode(values);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ConditionKey other)) {
                return false;
            }
            return this.kind.equals(other.kind) && Arrays.equals(this.values, other.values);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
