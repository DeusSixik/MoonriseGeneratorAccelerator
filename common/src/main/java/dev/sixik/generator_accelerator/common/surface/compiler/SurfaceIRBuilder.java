package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceConditionIR;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SurfaceIRBuilder {
    private static final SurfaceRuleIR.Empty EMPTY_RULE = new SurfaceRuleIR.Empty();
    private static final SurfaceConditionIR.AbovePreliminarySurface ABOVE_PRELIMINARY_SURFACE = new SurfaceConditionIR.AbovePreliminarySurface();
    private static final SurfaceConditionIR.Temperature TEMPERATURE = new SurfaceConditionIR.Temperature();
    private static final SurfaceConditionIR.Steep STEEP = new SurfaceConditionIR.Steep();
    private static final SurfaceConditionIR.Hole HOLE = new SurfaceConditionIR.Hole();
    private static final SurfaceConditionIR.Constant TRUE = new SurfaceConditionIR.Constant(true);
    private static final SurfaceConditionIR.Constant FALSE = new SurfaceConditionIR.Constant(false);

    private final HashMap<SurfaceConditionIR, Integer> conditionUseCounts = new HashMap<>();
    private int fallbackRuleCount;
    private int fallbackConditionCount;
    private boolean optimizerCandidate;
    private boolean fullOptimizerCandidate;

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
            locallyOptimizeSequence(flattened);
            if (flattened.isEmpty()) {
                return EMPTY_RULE;
            }
            if (flattened.size() == 1) {
                return flattened.get(0);
            }
            this.optimizerCandidate |= hasSequenceOptimizationOpportunity(flattened);
            this.fullOptimizerCandidate |= hasFullSequenceOptimizationOpportunity(flattened);
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
            SurfaceConditionIR target = buildCondition(notCondition.target());
            if (target instanceof SurfaceConditionIR.Constant constant) {
                return constant.value() ? FALSE : TRUE;
            }
            if (target instanceof SurfaceConditionIR.Not nested) {
                return nested.target();
            }
            this.optimizerCandidate |= target instanceof SurfaceConditionIR.AllOf
                    || target instanceof SurfaceConditionIR.AnyOf;
            this.fullOptimizerCandidate |= target instanceof SurfaceConditionIR.AllOf
                    || target instanceof SurfaceConditionIR.AnyOf;
            return new SurfaceConditionIR.Not(target);
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

    boolean optimizerCandidate() {
        return this.optimizerCandidate;
    }

    boolean fullOptimizerCandidate() {
        return this.fullOptimizerCandidate;
    }

    private SurfaceRuleIR buildTestRule(SurfaceRules.TestRuleSource testRule) {
        List<SurfaceConditionIR> conditions = new ArrayList<>(2);
        SurfaceRules.RuleSource finalRule = testRule;

        while (finalRule instanceof SurfaceRules.TestRuleSource currentTest) {
            conditions.add(buildCondition(currentTest.ifTrue()));
            finalRule = currentTest.thenRun();
        }

        SurfaceConditionIR combinedCondition = combineAll(conditions);
        if (combinedCondition instanceof SurfaceConditionIR.Constant constant) {
            return constant.value() ? buildRule(finalRule) : EMPTY_RULE;
        }
        countConditionUse(combinedCondition);
        return new SurfaceRuleIR.Test(combinedCondition, buildRule(finalRule));
    }

    private SurfaceConditionIR combineAll(List<SurfaceConditionIR> conditions) {
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        ArrayList<SurfaceConditionIR> deduped = new ArrayList<>(conditions.size());
        java.util.HashSet<SurfaceConditionIR> seen = new java.util.HashSet<>();
        for (SurfaceConditionIR condition : conditions) {
            if (condition instanceof SurfaceConditionIR.Constant constant) {
                if (!constant.value()) {
                    return FALSE;
                }
                continue;
            }
            if (hasComplement(seen, condition)) {
                return FALSE;
            }
            if (seen.add(condition)) {
                deduped.add(condition);
            }
        }
        if (deduped.isEmpty()) {
            return TRUE;
        }
        if (deduped.size() == 1) {
            return deduped.get(0);
        }
        if (isReorderSafe(deduped) && deduped.size() > 2) {
            deduped.sort(Comparator.comparingInt(this::estimatedCost));
        }
        this.optimizerCandidate |= hasBooleanOptimizationOpportunity(deduped);
        this.fullOptimizerCandidate |= hasFullBooleanOptimizationOpportunity(deduped);
        return new SurfaceConditionIR.AllOf(deduped);
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

    private void locallyOptimizeSequence(List<SurfaceRuleIR> rules) {
        for (int i = 0; i < rules.size(); i++) {
            SurfaceRuleIR rule = rules.get(i);
            if (rule instanceof SurfaceRuleIR.Empty) {
                rules.remove(i--);
                continue;
            }
            if (rule instanceof SurfaceRuleIR.Block) {
                rules.subList(i + 1, rules.size()).clear();
                break;
            }
        }
        mergeAdjacentSameBlockRules(rules);
    }

    private void mergeAdjacentSameBlockRules(List<SurfaceRuleIR> rules) {
        for (int i = 0; i < rules.size() - 1; i++) {
            SurfaceRuleIR current = rules.get(i);
            if (!(current instanceof SurfaceRuleIR.Test first)
                    || !(first.thenRun() instanceof SurfaceRuleIR.Block firstBlock)
                    || !isReorderSafe(first.condition())
                    || !isColumnOnly(first.condition())) {
                continue;
            }

            ArrayList<SurfaceConditionIR> mergedConditions = null;
            int j = i + 1;
            while (j < rules.size()) {
                SurfaceRuleIR next = rules.get(j);
                if (!(next instanceof SurfaceRuleIR.Test nextTest)
                        || !(nextTest.thenRun() instanceof SurfaceRuleIR.Block nextBlock)
                        || nextBlock.blockId() != firstBlock.blockId()
                        || nextBlock.mayWriteFluid() != firstBlock.mayWriteFluid()
                        || !isReorderSafe(nextTest.condition())
                        || !isColumnOnly(nextTest.condition())) {
                    break;
                }

                if (mergedConditions == null) {
                    mergedConditions = new ArrayList<>(4);
                    mergedConditions.add(first.condition());
                }
                mergedConditions.add(nextTest.condition());
                j++;
            }

            if (mergedConditions != null) {
                SurfaceConditionIR merged = combineAny(mergedConditions);
                rules.set(i, new SurfaceRuleIR.Test(merged, firstBlock));
                rules.subList(i + 1, j).clear();
            }
        }
    }

    private SurfaceConditionIR combineAny(List<SurfaceConditionIR> conditions) {
        ArrayList<SurfaceConditionIR> deduped = new ArrayList<>(conditions.size());
        java.util.HashSet<SurfaceConditionIR> seen = new java.util.HashSet<>();
        for (SurfaceConditionIR condition : conditions) {
            if (condition instanceof SurfaceConditionIR.Constant constant) {
                if (constant.value()) {
                    return TRUE;
                }
                continue;
            }
            if (hasComplement(seen, condition)) {
                return TRUE;
            }
            if (seen.add(condition)) {
                deduped.add(condition);
            }
        }
        if (deduped.isEmpty()) {
            return FALSE;
        }
        if (deduped.size() == 1) {
            return deduped.get(0);
        }
        if (isReorderSafe(deduped) && deduped.size() > 2) {
            deduped.sort(Comparator.comparingInt(this::estimatedCost));
        }
        return new SurfaceConditionIR.AnyOf(deduped);
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

    private boolean hasSequenceOptimizationOpportunity(List<SurfaceRuleIR> rules) {
        return hasFullSequenceOptimizationOpportunity(rules);
    }

    private boolean hasFullSequenceOptimizationOpportunity(List<SurfaceRuleIR> rules) {
        SurfaceRuleIR.Test previousTest = null;
        SurfaceRuleIR.Block previousBlock = null;
        for (int i = 0; i < rules.size(); i++) {
            SurfaceRuleIR rule = rules.get(i);
            if (rule instanceof SurfaceRuleIR.Block) {
                return i != rules.size() - 1;
            }
            if (rule instanceof SurfaceRuleIR.Test test && test.thenRun() instanceof SurfaceRuleIR.Block block) {
                if (previousTest != null
                        && previousBlock.blockId() == block.blockId()
                        && previousBlock.mayWriteFluid() == block.mayWriteFluid()
                        && isReorderSafe(previousTest.condition())
                        && isReorderSafe(test.condition())
                        && isColumnOnly(previousTest.condition())
                        && isColumnOnly(test.condition())) {
                    return true;
                }
                previousTest = test;
                previousBlock = block;
            } else {
                previousTest = null;
                previousBlock = null;
            }
        }
        return false;
    }

    private boolean hasBooleanOptimizationOpportunity(List<SurfaceConditionIR> conditions) {
        // Cheap AllOf pairs are already good enough for the lowerer. Only request
        // full DAG when folding/dedup/grouping can actually change the tree.
        return hasFullBooleanOptimizationOpportunity(conditions);
    }

    private boolean hasFullBooleanOptimizationOpportunity(List<SurfaceConditionIR> conditions) {
        java.util.HashSet<SurfaceConditionIR> seen = null;
        for (SurfaceConditionIR condition : conditions) {
            if (condition instanceof SurfaceConditionIR.Constant || condition instanceof SurfaceConditionIR.AllOf) {
                return true;
            }
            if (seen == null) {
                seen = new java.util.HashSet<>();
            }
            if (seen.contains(condition) || hasComplement(seen, condition)) {
                return true;
            }
            seen.add(condition);
        }
        return false;
    }

    private boolean hasComplement(java.util.HashSet<SurfaceConditionIR> seen, SurfaceConditionIR condition) {
        if (condition instanceof SurfaceConditionIR.Not not) {
            return seen.contains(not.target());
        }
        return seen.contains(new SurfaceConditionIR.Not(condition));
    }

    private boolean isReorderSafe(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> false;
            case SurfaceConditionIR.Not not -> isReorderSafe(not.target());
            case SurfaceConditionIR.AllOf allOf -> isReorderSafe(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isReorderSafe(anyOf.conditions());
            default -> true;
        };
    }

    private boolean isReorderSafe(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isReorderSafe(condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean isColumnOnly(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> true;
            case SurfaceConditionIR.BiomeCondition ignored -> true;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> true;
            case SurfaceConditionIR.NoiseThreshold ignored -> true;
            case SurfaceConditionIR.Hole ignored -> true;
            case SurfaceConditionIR.Steep ignored -> true;
            case SurfaceConditionIR.Not not -> isColumnOnly(not.target());
            case SurfaceConditionIR.AllOf allOf -> isColumnOnly(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isColumnOnly(anyOf.conditions());
            default -> false;
        };
    }

    private boolean isColumnOnly(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isColumnOnly(condition)) {
                return false;
            }
        }
        return true;
    }

    private int estimatedCost(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> 0;
            case SurfaceConditionIR.BiomeCondition ignored -> 10;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> 10;
            case SurfaceConditionIR.Hole ignored -> 12;
            case SurfaceConditionIR.Steep ignored -> 14;
            case SurfaceConditionIR.Y y -> y.addStoneDepth() ? 45 : 20;
            case SurfaceConditionIR.AbovePreliminarySurface ignored -> 20;
            case SurfaceConditionIR.Water water -> water.addStoneDepth() ? 50 : 25;
            case SurfaceConditionIR.StoneDepth ignored -> 55;
            case SurfaceConditionIR.NoiseThreshold ignored -> 70;
            case SurfaceConditionIR.Temperature ignored -> 80;
            case SurfaceConditionIR.VerticalGradient ignored -> 90;
            case SurfaceConditionIR.Not not -> 5 + estimatedCost(not.target());
            case SurfaceConditionIR.AllOf allOf -> listCost(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> listCost(anyOf.conditions());
            case SurfaceConditionIR.FallbackCondition ignored -> 1_000;
        };
    }

    private int listCost(List<SurfaceConditionIR> conditions) {
        int cost = 0;
        for (SurfaceConditionIR condition : conditions) {
            cost += estimatedCost(condition);
        }
        return cost;
    }
}
