package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceConditionIR;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SurfaceIRLowerer {
    private final Map<SurfaceConditionIR, Integer> conditionUseCounts;
    private final HashMap<SurfaceConditionIR, SurfaceConditionNode> compiledConditions = new HashMap<>();
    private int nextConditionCacheSlot;

    SurfaceIRLowerer() {
        this(new HashMap<>());
    }

    SurfaceIRLowerer(Map<SurfaceConditionIR, Integer> conditionUseCounts) {
        this.conditionUseCounts = conditionUseCounts;
    }

    SurfaceRuleNode lower(SurfaceRuleIR root) {
        countRuleConditions(root);
        return lowerRule(root);
    }

    LoweredProgram lowerProgram(SurfaceRuleIR root, int fallbackIslandCount) {
        int ruleCount = topLevelRuleCount(root);
        int[] opcodes = new int[ruleCount];
        int[] intOperands = new int[ruleCount];
        Object[] objectOperands = new Object[ruleCount];
        SurfaceProgramStep[] steps = new SurfaceProgramStep[ruleCount];
        boolean[] hasSpecialStep = new boolean[1];
        boolean hasGenericRule = false;

        if (root instanceof SurfaceRuleIR.Sequence sequence) {
            List<SurfaceRuleIR> rules = sequence.rules();
            for (int i = 0; i < rules.size(); i++) {
                hasGenericRule |= encodeTopLevelRule(rules.get(i), i, opcodes, intOperands, objectOperands, steps, hasSpecialStep);
            }
        } else if (!(root instanceof SurfaceRuleIR.Empty)) {
            hasGenericRule = encodeTopLevelRule(root, 0, opcodes, intOperands, objectOperands, steps, hasSpecialStep);
        }

        long facts = facts(root);
        SurfaceProgram program = hasSpecialStep[0]
                ? new SurfaceProgram(
                        opcodes,
                        intOperands,
                        objectOperands,
                        steps,
                        factsRequirements(facts),
                        fallbackIslandCount,
                        factsMayWriteFluid(facts)
                )
                : new SurfaceProgram(
                        opcodes,
                        intOperands,
                        objectOperands,
                factsRequirements(facts),
                fallbackIslandCount,
                factsMayWriteFluid(facts)
        );
        String rootNodeName = rootNodeName(root, hasGenericRule);
        return new LoweredProgram(program, rootNodeName);
    }

    int conditionCacheSlots() {
        return this.nextConditionCacheSlot;
    }

    int compiledConditionCount() {
        return this.compiledConditions.size();
    }

    private int topLevelRuleCount(SurfaceRuleIR root) {
        if (root instanceof SurfaceRuleIR.Empty) {
            return 0;
        }
        if (root instanceof SurfaceRuleIR.Sequence sequence) {
            return sequence.rules().size();
        }
        return 1;
    }

    private boolean encodeTopLevelRule(
            SurfaceRuleIR rule,
            int index,
            int[] opcodes,
            int[] intOperands,
            Object[] objectOperands,
            SurfaceProgramStep[] steps,
            boolean[] hasSpecialStep
    ) {
        if (rule instanceof SurfaceRuleIR.Block block) {
            opcodes[index] = SurfaceProgram.OP_BLOCK;
            intOperands[index] = block.blockId();
            steps[index] = new BlockProgramStep(block.blockId());
            return false;
        }

        if (rule instanceof SurfaceRuleIR.Test test && test.thenRun() instanceof SurfaceRuleIR.Block block) {
            opcodes[index] = SurfaceProgram.OP_TEST_BLOCK;
            intOperands[index] = block.blockId();
            SurfaceProgramStep specializedStep = SurfaceCompilerConfig.COLUMN_INTERVAL ? intervalStep(test.condition(), block.blockId()) : null;
            if (specializedStep != null) {
                steps[index] = specializedStep;
                hasSpecialStep[0] = true;
            } else {
                SurfaceConditionNode condition = lowerCondition(test.condition());
                objectOperands[index] = condition;
                steps[index] = new MaskTestBlockProgramStep(condition, block.blockId());
            }
            return false;
        }

        opcodes[index] = SurfaceProgram.OP_RULE;
        SurfaceRuleNode lowered = lowerRule(rule);
        objectOperands[index] = lowered;
        steps[index] = new RuleProgramStep(lowered);
        return true;
    }

    private String rootNodeName(SurfaceRuleIR root, boolean hasGenericRule) {
        if (root instanceof SurfaceRuleIR.Empty) {
            return EmptySurfaceRuleNode.class.getName();
        }
        if (hasGenericRule) {
            return "surface-ir-direct+generic";
        }
        if (root instanceof SurfaceRuleIR.Sequence) {
            return "surface-ir-direct-sequence";
        }
        return "surface-ir-direct";
    }

    private SurfaceRuleNode lowerRule(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> EmptySurfaceRuleNode.INSTANCE;
            case SurfaceRuleIR.Block block -> new BlockSurfaceRuleNode(block.blockId());
            case SurfaceRuleIR.Sequence sequence -> lowerSequence(sequence.rules());
            case SurfaceRuleIR.Test test -> lowerTest(test);
            case SurfaceRuleIR.FallbackRule fallback -> SurfaceRuleCompiler.compileLegacyRuleNode(fallback.source());
        };
    }

    private SurfaceRuleNode lowerSequence(List<SurfaceRuleIR> rules) {
        if (rules.isEmpty()) {
            return EmptySurfaceRuleNode.INSTANCE;
        }
        if (rules.size() == 1) {
            return lowerRule(rules.get(0));
        }

        SurfaceRuleNode[] lowered = new SurfaceRuleNode[rules.size()];
        for (int i = 0; i < lowered.length; i++) {
            lowered[i] = lowerRule(rules.get(i));
        }
        return new SequenceSurfaceRuleNode(lowered);
    }

    private SurfaceRuleNode lowerTest(SurfaceRuleIR.Test test) {
        SurfaceConditionNode condition = lowerCondition(test.condition());
        if (test.thenRun() instanceof SurfaceRuleIR.Block block) {
            return new TestBlockSurfaceRuleNode(condition, block.blockId());
        }
        return new TestSurfaceRuleNode(condition, lowerRule(test.thenRun()));
    }

    private SurfaceConditionNode lowerCondition(SurfaceConditionIR condition) {
        boolean cacheable = isCacheable(condition);
        if (cacheable) {
            SurfaceConditionNode existing = this.compiledConditions.get(condition);
            if (existing != null) {
                return existing;
            }
        }

        SurfaceConditionNode compiled = lowerConditionUncached(condition);
        if (cacheable && this.conditionUseCounts.getOrDefault(condition, 0) > 1) {
            compiled = new CachedSurfaceConditionNode(compiled, this.nextConditionCacheSlot++);
        }
        if (SurfaceMetrics.enabled() && cacheable) {
            String metricKind = compiled.getClass().getSimpleName();
            compiled = new TimedSurfaceConditionNode(compiled, metricKind);
        }

        if (cacheable) {
            this.compiledConditions.put(condition, compiled);
        }
        return compiled;
    }

    private SurfaceConditionNode lowerConditionUncached(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant constant -> constant.value()
                    ? ConstantSurfaceConditionNode.TRUE
                    : ConstantSurfaceConditionNode.FALSE;
            case SurfaceConditionIR.BiomeCondition biome -> new BiomeSurfaceConditionNode(biome.biomes());
            case SurfaceConditionIR.HolderSetBiomeCondition holderSetBiome -> new HolderSetBiomeSurfaceConditionNode(holderSetBiome.biomes());
            case SurfaceConditionIR.StoneDepth stoneDepth -> new StoneDepthSurfaceConditionNode(
                    stoneDepth.offset(),
                    stoneDepth.addSurfaceDepth(),
                    stoneDepth.secondaryDepthRange(),
                    stoneDepth.surfaceType()
            );
            case SurfaceConditionIR.Y y -> new YSurfaceConditionNode(
                    y.anchor(),
                    y.surfaceDepthMultiplier(),
                    y.addStoneDepth()
            );
            case SurfaceConditionIR.NoiseThreshold noiseThreshold -> new NoiseThresholdSurfaceConditionNode(
                    noiseThreshold.noiseKey(),
                    noiseThreshold.minThreshold(),
                    noiseThreshold.maxThreshold()
            );
            case SurfaceConditionIR.VerticalGradient verticalGradient -> new VerticalGradientSurfaceConditionNode(
                    verticalGradient.trueAtAndBelow(),
                    verticalGradient.falseAtAndAbove(),
                    verticalGradient.randomName()
            );
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> AbovePreliminarySurfaceConditionNode.INSTANCE;
            case SurfaceConditionIR.Water water -> new WaterSurfaceConditionNode(
                    water.offset(),
                    water.surfaceDepthMultiplier(),
                    water.addStoneDepth()
            );
            case SurfaceConditionIR.Temperature ignored -> TemperatureSurfaceConditionNode.INSTANCE;
            case SurfaceConditionIR.Steep ignored -> SteepSurfaceConditionNode.INSTANCE;
            case SurfaceConditionIR.Hole ignored -> HoleSurfaceConditionNode.INSTANCE;
            case SurfaceConditionIR.Not not -> new NotSurfaceConditionNode(lowerCondition(not.target()));
            case SurfaceConditionIR.AllOf allOf -> lowerAllOf(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> lowerAnyOf(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition fallback -> SurfaceRuleCompiler.compileLegacyConditionNode(fallback.source());
        };
    }

    private SurfaceProgramStep intervalStep(SurfaceConditionIR condition, int blockId) {
        if (condition instanceof SurfaceConditionIR.Y y && !y.addStoneDepth() && y.surfaceDepthMultiplier() == 0) {
            return new AnchorYTestBlockProgramStep(y.anchor(), blockId);
        }
        if (condition instanceof SurfaceConditionIR.AllOf allOf) {
            return allOfStep(allOf.conditions(), blockId);
        }
        IntervalConditionPlan intervalPlan = intervalPlan(condition);
        if (intervalPlan != null) {
            return new IntervalTestBlockProgramStep(intervalPlan, blockId);
        }
        ColumnConditionPlan columnPlan = columnPlan(condition);
        if (columnPlan != null) {
            return new ColumnTestBlockProgramStep(columnPlan, blockId);
        }
        return null;
    }

    private SurfaceProgramStep allOfStep(List<SurfaceConditionIR> conditions, int blockId) {
        if (conditions.isEmpty()) {
            return new BlockProgramStep(blockId);
        }

        ColumnConditionPlan[] columnPlans = new ColumnConditionPlan[conditions.size()];
        IntervalConditionPlan[] intervalPlans = new IntervalConditionPlan[conditions.size()];
        int columnCount = 0;
        int intervalCount = 0;
        SurfaceConditionIR.Y anchorY = null;

        for (SurfaceConditionIR condition : conditions) {
            ColumnConditionPlan columnPlan = columnPlan(condition);
            if (columnPlan != null) {
                columnPlans[columnCount++] = columnPlan;
                continue;
            }

            IntervalConditionPlan intervalPlan = intervalPlan(condition);
            if (intervalPlan != null) {
                intervalPlans[intervalCount++] = intervalPlan;
                if (condition instanceof SurfaceConditionIR.Y y && !y.addStoneDepth() && y.surfaceDepthMultiplier() == 0) {
                    anchorY = y;
                }
                continue;
            }

            return null;
        }

        ColumnConditionPlan columnPlan = columnCount == 0
                ? null
                : columnCount == 1
                ? columnPlans[0]
                : new AllOfColumnConditionPlan(java.util.Arrays.copyOf(columnPlans, columnCount));
        IntervalConditionPlan intervalPlan = intervalCount == 0
                ? null
                : intervalCount == 1
                ? intervalPlans[0]
                : new AllOfIntervalConditionPlan(java.util.Arrays.copyOf(intervalPlans, intervalCount));

        if (columnPlan != null && intervalPlan != null) {
            if (intervalCount == 1 && anchorY != null) {
                return new ColumnAnchorYTestBlockProgramStep(columnPlan, anchorY.anchor(), blockId);
            }
            return new ColumnIntervalTestBlockProgramStep(columnPlan, intervalPlan, blockId);
        }
        if (columnPlan != null) {
            return new ColumnTestBlockProgramStep(columnPlan, blockId);
        }
        return intervalPlan == null ? null : new IntervalTestBlockProgramStep(intervalPlan, blockId);
    }

    private ColumnConditionPlan columnPlan(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant constant -> constant.value()
                    ? TrueColumnConditionPlan.INSTANCE
                    : FalseColumnConditionPlan.INSTANCE;
            case SurfaceConditionIR.BiomeCondition biome -> new BiomeColumnConditionPlan(biome.biomes());
            case SurfaceConditionIR.HolderSetBiomeCondition holderSetBiome -> new HolderSetBiomeColumnConditionPlan(holderSetBiome.biomes());
            case SurfaceConditionIR.NoiseThreshold noiseThreshold -> new NoiseThresholdColumnConditionPlan(
                    noiseThreshold.noiseKey(),
                    noiseThreshold.minThreshold(),
                    noiseThreshold.maxThreshold()
            );
            case SurfaceConditionIR.Hole ignored -> HoleColumnConditionPlan.INSTANCE;
            case SurfaceConditionIR.Steep ignored -> SteepColumnConditionPlan.INSTANCE;
            case SurfaceConditionIR.Not not -> {
                ColumnConditionPlan target = columnPlan(not.target());
                yield target == null ? null : new NotColumnConditionPlan(target);
            }
            case SurfaceConditionIR.AllOf allOf -> columnAllOfPlan(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> columnAnyOfPlan(anyOf.conditions());
            default -> null;
        };
    }

    private ColumnConditionPlan columnAllOfPlan(List<SurfaceConditionIR> conditions) {
        if (conditions.isEmpty()) {
            return TrueColumnConditionPlan.INSTANCE;
        }

        ColumnConditionPlan[] plans = new ColumnConditionPlan[conditions.size()];
        for (int i = 0; i < conditions.size(); i++) {
            ColumnConditionPlan plan = columnPlan(conditions.get(i));
            if (plan == null) {
                return null;
            }
            plans[i] = plan;
        }
        if (plans.length == 1) {
            return plans[0];
        }
        return new AllOfColumnConditionPlan(plans);
    }

    private ColumnConditionPlan columnAnyOfPlan(List<SurfaceConditionIR> conditions) {
        if (conditions.isEmpty()) {
            return FalseColumnConditionPlan.INSTANCE;
        }

        ColumnConditionPlan[] plans = new ColumnConditionPlan[conditions.size()];
        for (int i = 0; i < conditions.size(); i++) {
            ColumnConditionPlan plan = columnPlan(conditions.get(i));
            if (plan == null) {
                return null;
            }
            plans[i] = plan;
        }
        if (plans.length == 1) {
            return plans[0];
        }
        return new AnyOfColumnConditionPlan(plans);
    }

    private IntervalConditionPlan intervalPlan(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant constant -> constant.value()
                    ? TrueIntervalConditionPlan.INSTANCE
                    : FalseIntervalConditionPlan.INSTANCE;
            case SurfaceConditionIR.Y y -> y.addStoneDepth()
                    ? null
                    : new YIntervalConditionPlan(y.anchor(), y.surfaceDepthMultiplier());
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> AbovePreliminaryIntervalConditionPlan.INSTANCE;
            case SurfaceConditionIR.Water water -> water.addStoneDepth()
                    ? null
                    : new WaterIntervalConditionPlan(water.offset(), water.surfaceDepthMultiplier());
            case SurfaceConditionIR.AllOf allOf -> intervalAllOfPlan(allOf.conditions());
            default -> null;
        };
    }

    private IntervalConditionPlan intervalAllOfPlan(List<SurfaceConditionIR> conditions) {
        if (conditions.isEmpty()) {
            return TrueIntervalConditionPlan.INSTANCE;
        }

        IntervalConditionPlan[] plans = new IntervalConditionPlan[conditions.size()];
        for (int i = 0; i < conditions.size(); i++) {
            IntervalConditionPlan plan = intervalPlan(conditions.get(i));
            if (plan == null) {
                return null;
            }
            plans[i] = plan;
        }
        if (plans.length == 1) {
            return plans[0];
        }
        return new AllOfIntervalConditionPlan(plans);
    }

    private SurfaceConditionNode lowerAllOf(List<SurfaceConditionIR> conditions) {
        if (conditions.isEmpty()) {
            return ConstantSurfaceConditionNode.TRUE;
        }
        if (conditions.size() == 1) {
            return lowerCondition(conditions.get(0));
        }

        SurfaceConditionNode[] lowered = new SurfaceConditionNode[conditions.size()];
        for (int i = 0; i < lowered.length; i++) {
            lowered[i] = lowerCondition(conditions.get(i));
        }
        return new AllOfSurfaceConditionNode(lowered);
    }

    private SurfaceConditionNode lowerAnyOf(List<SurfaceConditionIR> conditions) {
        if (conditions.isEmpty()) {
            return ConstantSurfaceConditionNode.FALSE;
        }
        if (conditions.size() == 1) {
            return lowerCondition(conditions.get(0));
        }

        SurfaceConditionNode[] lowered = new SurfaceConditionNode[conditions.size()];
        for (int i = 0; i < lowered.length; i++) {
            lowered[i] = lowerCondition(conditions.get(i));
        }
        return new AnyOfSurfaceConditionNode(lowered);
    }

    private void countRuleConditions(SurfaceRuleIR rule) {
        switch (rule) {
            case SurfaceRuleIR.Empty ignored -> {
            }
            case SurfaceRuleIR.Block ignored -> {
            }
            case SurfaceRuleIR.FallbackRule ignored -> {
            }
            case SurfaceRuleIR.Sequence sequence -> {
                for (SurfaceRuleIR child : sequence.rules()) {
                    countRuleConditions(child);
                }
            }
            case SurfaceRuleIR.Test test -> {
                countCondition(test.condition());
                countRuleConditions(test.thenRun());
            }
        }
    }

    private void countCondition(SurfaceConditionIR condition) {
        this.conditionUseCounts.put(condition, this.conditionUseCounts.getOrDefault(condition, 0) + 1);
        switch (condition) {
            case SurfaceConditionIR.Not not -> countCondition(not.target());
            case SurfaceConditionIR.AllOf allOf -> {
                for (SurfaceConditionIR child : allOf.conditions()) {
                    countCondition(child);
                }
            }
            case SurfaceConditionIR.AnyOf anyOf -> {
                for (SurfaceConditionIR child : anyOf.conditions()) {
                    countCondition(child);
                }
            }
            case SurfaceConditionIR.FallbackCondition ignored -> {
            }
            default -> {
            }
        }
    }

    private boolean isCacheable(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> false;
            case SurfaceConditionIR.Not not -> isCacheable(not.target());
            case SurfaceConditionIR.AllOf allOf -> isCacheable(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isCacheable(anyOf.conditions());
            default -> true;
        };
    }

    private boolean isCacheable(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isCacheable(condition)) {
                return false;
            }
        }
        return true;
    }

    private long facts(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> packFacts(0, false);
            case SurfaceRuleIR.Block block -> packFacts(0, block.mayWriteFluid());
            case SurfaceRuleIR.FallbackRule ignored -> packFacts(fallbackRequirements(), true);
            case SurfaceRuleIR.Sequence sequence -> {
                int requirements = 0;
                boolean mayWriteFluid = false;
                for (SurfaceRuleIR child : sequence.rules()) {
                    long childFacts = facts(child);
                    requirements |= factsRequirements(childFacts);
                    mayWriteFluid |= factsMayWriteFluid(childFacts);
                }
                yield packFacts(requirements, mayWriteFluid);
            }
            case SurfaceRuleIR.Test test -> {
                long thenFacts = facts(test.thenRun());
                yield packFacts(
                        conditionRequirements(test.condition()) | factsRequirements(thenFacts),
                        factsMayWriteFluid(thenFacts)
                );
            }
        };
    }

    private int conditionRequirements(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> 0;
            case SurfaceConditionIR.BiomeCondition ignored -> SurfaceRequirements.BIOME;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> SurfaceRequirements.BIOME;
            case SurfaceConditionIR.StoneDepth stoneDepth -> {
                int requirements = SurfaceRequirements.STONE_DEPTH;
                if (stoneDepth.addSurfaceDepth() || stoneDepth.secondaryDepthRange() != 0) {
                    requirements |= SurfaceRequirements.SURFACE_DEPTH;
                }
                if (stoneDepth.secondaryDepthRange() != 0) {
                    requirements |= SurfaceRequirements.SECONDARY_SURFACE;
                }
                yield requirements;
            }
            case SurfaceConditionIR.Y y -> {
                int requirements = SurfaceRequirements.SURFACE_DEPTH;
                if (y.addStoneDepth()) {
                    requirements |= SurfaceRequirements.STONE_DEPTH;
                }
                yield requirements;
            }
            case SurfaceConditionIR.NoiseThreshold ignored -> SurfaceRequirements.NOISE;
            case SurfaceConditionIR.VerticalGradient ignored -> SurfaceRequirements.RANDOM;
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> SurfaceRequirements.PRELIMINARY_SURFACE;
            case SurfaceConditionIR.Water water -> {
                int requirements = SurfaceRequirements.WATER | SurfaceRequirements.SURFACE_DEPTH;
                if (water.addStoneDepth()) {
                    requirements |= SurfaceRequirements.STONE_DEPTH;
                }
                yield requirements;
            }
            case SurfaceConditionIR.Temperature ignored -> SurfaceRequirements.TEMPERATURE | SurfaceRequirements.BIOME;
            case SurfaceConditionIR.Steep ignored -> SurfaceRequirements.SLOPE;
            case SurfaceConditionIR.Hole ignored -> SurfaceRequirements.SURFACE_DEPTH;
            case SurfaceConditionIR.Not not -> conditionRequirements(not.target());
            case SurfaceConditionIR.AllOf allOf -> conditionListRequirements(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> conditionListRequirements(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> fallbackRequirements();
        };
    }

    private int conditionListRequirements(List<SurfaceConditionIR> conditions) {
        int requirements = 0;
        for (SurfaceConditionIR condition : conditions) {
            requirements |= conditionRequirements(condition);
        }
        return requirements;
    }

    private int fallbackRequirements() {
        return SurfaceRequirements.FALLBACK
                | SurfaceRequirements.BIOME
                | SurfaceRequirements.STONE_DEPTH
                | SurfaceRequirements.WATER
                | SurfaceRequirements.SURFACE_DEPTH
                | SurfaceRequirements.SECONDARY_SURFACE
                | SurfaceRequirements.PRELIMINARY_SURFACE
                | SurfaceRequirements.TEMPERATURE
                | SurfaceRequirements.NOISE
                | SurfaceRequirements.RANDOM
                | SurfaceRequirements.SLOPE;
    }

    private long packFacts(int requirements, boolean mayWriteFluid) {
        return (requirements & 0xFFFF_FFFFL) | (mayWriteFluid ? 1L << 63 : 0L);
    }

    private int factsRequirements(long facts) {
        return (int) facts;
    }

    private boolean factsMayWriteFluid(long facts) {
        return facts < 0L;
    }

    record LoweredProgram(SurfaceProgram program, String rootNodeName) {
    }
}
