package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceConditionIR;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class SurfaceIROptimizer {
    private static final SurfaceRuleIR.Empty EMPTY_RULE = new SurfaceRuleIR.Empty();
    private static final SurfaceConditionIR.Constant TRUE = new SurfaceConditionIR.Constant(true);
    private static final SurfaceConditionIR.Constant FALSE = new SurfaceConditionIR.Constant(false);

    private final HashMap<SurfaceConditionIR, SurfaceConditionIR> canonicalConditions = new HashMap<>();
    private final HashMap<SurfaceConditionIR, Integer> conditionUseCounts = new HashMap<>();

    private SurfaceIROptimizer() {
    }

    static Result optimize(SurfaceRuleIR root) {
        SurfaceIROptimizer optimizer = new SurfaceIROptimizer();
        SurfaceRuleIR optimized = optimizer.optimizeRule(root);
        optimizer.countRuleConditions(optimized);
        return new Result(optimized, optimizer.conditionUseCounts);
    }

    static boolean hasOpportunity(SurfaceRuleIR root) {
        return switch (root) {
            case SurfaceRuleIR.Empty ignored -> false;
            case SurfaceRuleIR.Block ignored -> false;
            case SurfaceRuleIR.FallbackRule ignored -> false;
            case SurfaceRuleIR.Test test -> hasConditionOpportunity(test.condition()) || hasOpportunity(test.thenRun());
            case SurfaceRuleIR.Sequence sequence -> hasSequenceOpportunity(sequence.rules());
        };
    }

    private static boolean hasSequenceOpportunity(List<SurfaceRuleIR> rules) {
        SurfaceRuleIR.Test previousTest = null;
        SurfaceRuleIR.Block previousBlock = null;
        for (int i = 0; i < rules.size(); i++) {
            SurfaceRuleIR rule = rules.get(i);
            if (hasOpportunity(rule)) {
                return true;
            }
            if (rule instanceof SurfaceRuleIR.Sequence || rule instanceof SurfaceRuleIR.Empty) {
                return true;
            }
            if (rule instanceof SurfaceRuleIR.Test test && test.thenRun() instanceof SurfaceRuleIR.Block block) {
                if (previousTest != null
                        && previousBlock.blockId() == block.blockId()
                        && previousBlock.mayWriteFluid() == block.mayWriteFluid()
                        && isStaticReorderSafe(previousTest.condition())
                        && isStaticReorderSafe(test.condition())
                        && isStaticColumnOnly(previousTest.condition())
                        && isStaticColumnOnly(test.condition())) {
                    return true;
                }
                previousTest = test;
                previousBlock = block;
            } else {
                previousTest = null;
                previousBlock = null;
            }
            if (rule instanceof SurfaceRuleIR.Block) {
                return i != rules.size() - 1;
            }
        }
        return false;
    }

    private static boolean hasConditionOpportunity(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> true;
            case SurfaceConditionIR.Not not -> not.target() instanceof SurfaceConditionIR.Constant
                    || not.target() instanceof SurfaceConditionIR.Not
                    || not.target() instanceof SurfaceConditionIR.AllOf
                    || not.target() instanceof SurfaceConditionIR.AnyOf
                    || hasConditionOpportunity(not.target());
            case SurfaceConditionIR.AllOf allOf -> hasBooleanListOpportunity(allOf.conditions(), true);
            case SurfaceConditionIR.AnyOf anyOf -> true;
            default -> false;
        };
    }

    private static boolean hasBooleanListOpportunity(List<SurfaceConditionIR> conditions, boolean allOf) {
        HashSet<SurfaceConditionIR> seen = null;
        for (SurfaceConditionIR condition : conditions) {
            if (condition instanceof SurfaceConditionIR.Constant) {
                return true;
            }
            if ((allOf && condition instanceof SurfaceConditionIR.AllOf)
                    || (!allOf && condition instanceof SurfaceConditionIR.AnyOf)
                    || hasConditionOpportunity(condition)) {
                return true;
            }
            if (seen == null) {
                seen = new HashSet<>();
            }
            if (seen.contains(condition) || hasStaticComplement(seen, condition)) {
                return true;
            }
            seen.add(condition);
        }
        return false;
    }

    private static boolean hasStaticComplement(HashSet<SurfaceConditionIR> seen, SurfaceConditionIR condition) {
        if (condition instanceof SurfaceConditionIR.Not not) {
            return seen.contains(not.target());
        }
        return seen.contains(new SurfaceConditionIR.Not(condition));
    }

    private static boolean isStaticReorderSafe(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> false;
            case SurfaceConditionIR.Not not -> isStaticReorderSafe(not.target());
            case SurfaceConditionIR.AllOf allOf -> isStaticReorderSafe(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isStaticReorderSafe(anyOf.conditions());
            default -> true;
        };
    }

    private static boolean isStaticReorderSafe(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isStaticReorderSafe(condition)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStaticColumnOnly(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant ignored -> true;
            case SurfaceConditionIR.BiomeCondition ignored -> true;
            case SurfaceConditionIR.HolderSetBiomeCondition ignored -> true;
            case SurfaceConditionIR.NoiseThreshold ignored -> true;
            case SurfaceConditionIR.Hole ignored -> true;
            case SurfaceConditionIR.Steep ignored -> true;
            case SurfaceConditionIR.Not not -> isStaticColumnOnly(not.target());
            case SurfaceConditionIR.AllOf allOf -> isStaticColumnOnly(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isStaticColumnOnly(anyOf.conditions());
            default -> false;
        };
    }

    private static boolean isStaticColumnOnly(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isStaticColumnOnly(condition)) {
                return false;
            }
        }
        return true;
    }

    private SurfaceRuleIR optimizeRule(SurfaceRuleIR rule) {
        return switch (rule) {
            case SurfaceRuleIR.Empty ignored -> EMPTY_RULE;
            case SurfaceRuleIR.Block block -> block;
            case SurfaceRuleIR.FallbackRule fallback -> fallback;
            case SurfaceRuleIR.Test test -> optimizeTest(test);
            case SurfaceRuleIR.Sequence sequence -> optimizeSequence(sequence.rules());
        };
    }

    private SurfaceRuleIR optimizeTest(SurfaceRuleIR.Test test) {
        SurfaceConditionIR condition = optimizeCondition(test.condition());
        SurfaceRuleIR thenRun = optimizeRule(test.thenRun());

        if (condition instanceof SurfaceConditionIR.Constant constant) {
            return constant.value() ? thenRun : EMPTY_RULE;
        }
        if (thenRun instanceof SurfaceRuleIR.Empty) {
            return EMPTY_RULE;
        }
        return new SurfaceRuleIR.Test(condition, thenRun);
    }

    private SurfaceRuleIR optimizeSequence(List<SurfaceRuleIR> rules) {
        ArrayList<SurfaceRuleIR> flattened = new ArrayList<>(rules.size());
        for (SurfaceRuleIR child : rules) {
            SurfaceRuleIR optimized = optimizeRule(child);
            if (optimized instanceof SurfaceRuleIR.Empty) {
                continue;
            }
            if (optimized instanceof SurfaceRuleIR.Sequence sequence) {
                flattened.addAll(sequence.rules());
            } else {
                flattened.add(optimized);
            }
            if (optimized instanceof SurfaceRuleIR.Block) {
                break;
            }
        }

        if (flattened.isEmpty()) {
            return EMPTY_RULE;
        }
        if (flattened.size() == 1) {
            return flattened.get(0);
        }
        mergeAdjacentSameBlockRules(flattened);
        if (flattened.size() == 1) {
            return flattened.get(0);
        }
        return new SurfaceRuleIR.Sequence(flattened);
    }

    private void mergeAdjacentSameBlockRules(ArrayList<SurfaceRuleIR> rules) {
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
                SurfaceConditionIR merged = optimizeAnyOf(mergedConditions);
                rules.set(i, new SurfaceRuleIR.Test(merged, firstBlock));
                rules.subList(i + 1, j).clear();
            }
        }
    }

    private SurfaceConditionIR optimizeCondition(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.Constant constant -> constant.value() ? TRUE : FALSE;
            case SurfaceConditionIR.Not not -> optimizeNot(not.target());
            case SurfaceConditionIR.AllOf allOf -> optimizeAllOf(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> optimizeAnyOf(anyOf.conditions());
            default -> condition;
        };
    }

    private SurfaceConditionIR optimizeNot(SurfaceConditionIR target) {
        SurfaceConditionIR optimizedTarget = optimizeCondition(target);
        if (optimizedTarget instanceof SurfaceConditionIR.Constant constant) {
            return constant.value() ? FALSE : TRUE;
        }
        if (optimizedTarget instanceof SurfaceConditionIR.Not nested) {
            return optimizeCondition(nested.target());
        }
        return new SurfaceConditionIR.Not(optimizedTarget);
    }

    private SurfaceConditionIR optimizeAllOf(List<SurfaceConditionIR> conditions) {
        ArrayList<SurfaceConditionIR> flattened = new ArrayList<>(conditions.size());
        boolean reorderSafe = true;
        for (SurfaceConditionIR condition : conditions) {
            SurfaceConditionIR optimized = optimizeCondition(condition);
            if (optimized instanceof SurfaceConditionIR.Constant constant) {
                if (!constant.value()) {
                    return FALSE;
                }
                continue;
            }
            if (optimized instanceof SurfaceConditionIR.AllOf allOf) {
                flattened.addAll(allOf.conditions());
            } else {
                flattened.add(optimized);
            }
            reorderSafe &= isReorderSafe(optimized);
        }

        return finishBooleanList(flattened, true, reorderSafe);
    }

    private SurfaceConditionIR optimizeAnyOf(List<SurfaceConditionIR> conditions) {
        ArrayList<SurfaceConditionIR> flattened = new ArrayList<>(conditions.size());
        boolean reorderSafe = true;
        for (SurfaceConditionIR condition : conditions) {
            SurfaceConditionIR optimized = optimizeCondition(condition);
            if (optimized instanceof SurfaceConditionIR.Constant constant) {
                if (constant.value()) {
                    return TRUE;
                }
                continue;
            }
            if (optimized instanceof SurfaceConditionIR.AnyOf anyOf) {
                flattened.addAll(anyOf.conditions());
            } else {
                flattened.add(optimized);
            }
            reorderSafe &= isReorderSafe(optimized);
        }

        return finishBooleanList(flattened, false, reorderSafe);
    }

    private SurfaceConditionIR finishBooleanList(ArrayList<SurfaceConditionIR> conditions, boolean allOf, boolean reorderSafe) {
        if (conditions.isEmpty()) {
            return allOf ? TRUE : FALSE;
        }

        ArrayList<SurfaceConditionIR> deduped = new ArrayList<>(conditions.size());
        HashSet<SurfaceConditionIR> seen = new HashSet<>();
        for (SurfaceConditionIR condition : conditions) {
            SurfaceConditionIR canonical = isCanonicalSafe(condition) ? canonical(condition) : condition;
            if (hasComplement(seen, canonical)) {
                return allOf ? FALSE : TRUE;
            }
            if (seen.add(canonical)) {
                deduped.add(canonical);
            }
        }

        if (deduped.size() == 1) {
            return deduped.get(0);
        }
        if (reorderSafe && deduped.size() > 2) {
            deduped.sort(Comparator.comparingInt(this::estimatedCost));
        }
        return allOf ? new SurfaceConditionIR.AllOf(deduped) : new SurfaceConditionIR.AnyOf(deduped);
    }

    private boolean hasComplement(HashSet<SurfaceConditionIR> seen, SurfaceConditionIR condition) {
        if (condition instanceof SurfaceConditionIR.Not not) {
            return seen.contains(not.target());
        }
        return seen.contains(new SurfaceConditionIR.Not(condition));
    }

    private SurfaceConditionIR canonical(SurfaceConditionIR condition) {
        SurfaceConditionIR existing = this.canonicalConditions.putIfAbsent(condition, condition);
        return existing == null ? condition : existing;
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

    private boolean isRewriteSafe(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isReorderSafe(condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean isReorderSafe(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> false;
            case SurfaceConditionIR.Not not -> isReorderSafe(not.target());
            case SurfaceConditionIR.AllOf allOf -> isRewriteSafe(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isRewriteSafe(anyOf.conditions());
            default -> true;
        };
    }

    private boolean isColumnOnly(SurfaceConditionIR condition) {
        return isStaticColumnOnly(condition);
    }

    private boolean isCanonicalSafe(SurfaceConditionIR condition) {
        return switch (condition) {
            case SurfaceConditionIR.FallbackCondition ignored -> false;
            case SurfaceConditionIR.Not not -> isCanonicalSafe(not.target());
            case SurfaceConditionIR.AllOf allOf -> isCanonicalSafe(allOf.conditions());
            case SurfaceConditionIR.AnyOf anyOf -> isCanonicalSafe(anyOf.conditions());
            default -> true;
        };
    }

    private boolean isCanonicalSafe(List<SurfaceConditionIR> conditions) {
        for (SurfaceConditionIR condition : conditions) {
            if (!isCanonicalSafe(condition)) {
                return false;
            }
        }
        return true;
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
            default -> {
            }
        }
    }

    record Result(SurfaceRuleIR ir, Map<SurfaceConditionIR, Integer> conditionUseCounts) {
    }
}
