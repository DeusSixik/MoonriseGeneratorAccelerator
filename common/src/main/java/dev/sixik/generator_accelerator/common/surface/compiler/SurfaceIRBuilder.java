package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceConditionIR;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SurfaceIRBuilder {
    private static final SurfaceRuleIR.Empty EMPTY_RULE = new SurfaceRuleIR.Empty();
    private static final SurfaceConditionIR.AbovePreliminarySurface ABOVE_PRELIMINARY_SURFACE = new SurfaceConditionIR.AbovePreliminarySurface();
    private static final SurfaceConditionIR.Temperature TEMPERATURE = new SurfaceConditionIR.Temperature();
    private static final SurfaceConditionIR.Steep STEEP = new SurfaceConditionIR.Steep();
    private static final SurfaceConditionIR.Hole HOLE = new SurfaceConditionIR.Hole();

    private final HashMap<SurfaceConditionIR, Integer> conditionUseCounts = new HashMap<>();
    private int fallbackRuleCount;
    private int fallbackConditionCount;

    SurfaceRuleIR buildRule(SurfaceRules.RuleSource ruleSource) {
        if (ruleSource instanceof SurfaceRules.BlockRuleSource blockRule) {
            int blockId = Block.getId(blockRule.resultState());
            boolean mayWriteFluid = !blockRule.resultState().getFluidState().isEmpty();
            return new SurfaceRuleIR.Block(blockId, mayWriteFluid);
        }

        if (ruleSource instanceof SurfaceRules.TestRuleSource testRule) {
            return buildTestRule(testRule);
        }

        if (ruleSource instanceof SurfaceRules.SequenceRuleSource sequenceRule) {
            List<SurfaceRuleIR> flattened = new ArrayList<>(sequenceRule.sequence().size());
            flattenSequence(sequenceRule, flattened);
            if (flattened.isEmpty()) {
                return EMPTY_RULE;
            }
            if (flattened.size() == 1) {
                return flattened.get(0);
            }
            return new SurfaceRuleIR.Sequence(flattened);
        }

        this.fallbackRuleCount++;
        return new SurfaceRuleIR.FallbackRule(ruleSource);
    }

    SurfaceConditionIR buildCondition(SurfaceRules.ConditionSource conditionSource) {
        if (conditionSource instanceof SurfaceRules.BiomeConditionSource biomeCondition) {
            return new SurfaceConditionIR.BiomeCondition(biomeCondition.biomes);
        }

        if (conditionSource instanceof SurfaceRules.StoneDepthCheck stoneDepthCheck) {
            return new SurfaceConditionIR.StoneDepth(
                    stoneDepthCheck.offset(),
                    stoneDepthCheck.addSurfaceDepth(),
                    stoneDepthCheck.secondaryDepthRange(),
                    stoneDepthCheck.surfaceType()
            );
        }

        if (conditionSource instanceof SurfaceRules.YConditionSource yCondition) {
            return new SurfaceConditionIR.Y(
                    yCondition.anchor(),
                    yCondition.surfaceDepthMultiplier(),
                    yCondition.addStoneDepth()
            );
        }

        if (conditionSource instanceof SurfaceRules.NotConditionSource notCondition) {
            return new SurfaceConditionIR.Not(buildCondition(notCondition.target()));
        }

        if (conditionSource instanceof SurfaceRules.NoiseThresholdConditionSource noiseThresholdCondition) {
            return new SurfaceConditionIR.NoiseThreshold(
                    noiseThresholdCondition.noise(),
                    noiseThresholdCondition.minThreshold(),
                    noiseThresholdCondition.maxThreshold()
            );
        }

        if (conditionSource instanceof SurfaceRules.VerticalGradientConditionSource verticalGradientCondition) {
            return new SurfaceConditionIR.VerticalGradient(
                    verticalGradientCondition.trueAtAndBelow(),
                    verticalGradientCondition.falseAtAndAbove(),
                    verticalGradientCondition.randomName()
            );
        }

        if (conditionSource == SurfaceRules.AbovePreliminarySurface.INSTANCE) {
            return ABOVE_PRELIMINARY_SURFACE;
        }

        if (conditionSource instanceof SurfaceRules.WaterConditionSource waterCondition) {
            return new SurfaceConditionIR.Water(
                    waterCondition.offset(),
                    waterCondition.surfaceDepthMultiplier(),
                    waterCondition.addStoneDepth()
            );
        }

        if (conditionSource == SurfaceRules.Temperature.INSTANCE) {
            return TEMPERATURE;
        }

        if (conditionSource == SurfaceRules.Steep.INSTANCE) {
            return STEEP;
        }

        if (conditionSource == SurfaceRules.Hole.INSTANCE) {
            return HOLE;
        }

        this.fallbackConditionCount++;
        return new SurfaceConditionIR.FallbackCondition(conditionSource);
    }

    int fallbackRuleCount() {
        return this.fallbackRuleCount;
    }

    int fallbackConditionCount() {
        return this.fallbackConditionCount;
    }

    private SurfaceRuleIR buildTestRule(SurfaceRules.TestRuleSource testRule) {
        List<SurfaceConditionIR> conditions = new ArrayList<>(2);
        SurfaceRules.RuleSource finalRule = testRule;

        while (finalRule instanceof SurfaceRules.TestRuleSource currentTest) {
            conditions.add(buildCondition(currentTest.ifTrue()));
            finalRule = currentTest.thenRun();
        }

        SurfaceConditionIR combinedCondition = combineAll(conditions);
        countConditionUse(combinedCondition);
        return new SurfaceRuleIR.Test(combinedCondition, buildRule(finalRule));
    }

    private SurfaceConditionIR combineAll(List<SurfaceConditionIR> conditions) {
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        return new SurfaceConditionIR.AllOf(conditions);
    }

    Map<SurfaceConditionIR, Integer> conditionUseCounts() {
        return this.conditionUseCounts;
    }

    private void flattenSequence(SurfaceRules.SequenceRuleSource sequenceRule, List<SurfaceRuleIR> target) {
        for (SurfaceRules.RuleSource child : sequenceRule.sequence()) {
            if (child instanceof SurfaceRules.SequenceRuleSource childSequence) {
                flattenSequence(childSequence, target);
            } else {
                target.add(buildRule(child));
            }
        }
    }

    private void countConditionUse(SurfaceConditionIR condition) {
        this.conditionUseCounts.put(condition, this.conditionUseCounts.getOrDefault(condition, 0) + 1);
        switch (condition) {
            case SurfaceConditionIR.Not not -> countConditionUse(not.target());
            case SurfaceConditionIR.AllOf allOf -> countConditionUse(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> countConditionUse(anyOf.conditions());
            default -> {
            }
        }
    }

    private void countConditionUse(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            countConditionUse(condition);
        }
    }
}
