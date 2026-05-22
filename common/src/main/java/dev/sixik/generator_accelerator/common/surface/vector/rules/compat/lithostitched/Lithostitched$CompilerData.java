package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import dev.sixik.generator_accelerator.common.surface.vector.rules.VectorSequenceRule;
import dev.worldgen.lithostitched.worldgen.surface.condition.AllOfCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.AnyOfCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.BiomeCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.SlopeCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.internal.TagFilledCondition;
import dev.worldgen.lithostitched.worldgen.surface.rule.BandlandsRule;
import dev.worldgen.lithostitched.worldgen.surface.rule.ReferenceRule;
import dev.worldgen.lithostitched.worldgen.surface.rule.TransientMergedRule;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Lithostitched$CompilerData {



    @Nullable
    public static VectorRule compileRule(SurfaceRules.RuleSource ruleSource) {

        if (ruleSource instanceof TransientMergedRule mergedRule) {
            List<SurfaceRules.RuleSource> rules = mergedRule.rules();
            if (rules.size() == 1) {
                return VectorRuleCompiler.compileRule(rules.getFirst());
            } else {
                List<VectorRule> compiledList = new ObjectArrayList<>();

                for (SurfaceRules.RuleSource childRule : rules) {
                    compiledList.add(VectorRuleCompiler.compileRule(childRule));
                }

                compiledList.add(VectorRuleCompiler.compileRule(mergedRule.original()));
                return new VectorTransientMergedRule(compiledList.toArray(new VectorRule[0]));
            }
        }

        if (ruleSource instanceof ReferenceRule refRule) {
            int size = refRule.rules().size();

            if (size == 0) {
                return (rawBlockData, activeMask, ctx) -> {};
            } else if (size == 1) {
                return VectorRuleCompiler.compileRule(refRule.rules().get(0).value());
            } else {
                List<VectorRule> compiledList = new ObjectArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    compiledList.add(VectorRuleCompiler.compileRule(refRule.rules().get(i).value()));
                }
                return new VectorSequenceRule(compiledList.toArray(new VectorRule[0]));
            }
        }

        if (ruleSource instanceof BandlandsRule bandlandsRule) {
            return new VectorBandlandsRule(bandlandsRule.options().value());
        }

        return null;
    }

    @Nullable
    public static VectorCondition compileCondition(SurfaceRules.ConditionSource conditionSource) {

        if (conditionSource instanceof SlopeCondition slopeCond) {
            return new VectorSlopeCondition(slopeCond.threshold());
        }

        if (conditionSource instanceof BiomeCondition biomeCond) {
            return new VectorLithoBiomeCondition(biomeCond.biomes());
        }

        if (conditionSource instanceof AnyOfCondition anyOf) {
            List<VectorCondition> list = new ObjectArrayList<>(anyOf.conditions().size());
            for (SurfaceRules.ConditionSource cond : anyOf.conditions()) {
                list.add(VectorRuleCompiler.compileCondition(cond));
            }
            return new VectorAnyOfCondition(list.toArray(new VectorCondition[0]));
        }

        if (conditionSource instanceof AllOfCondition allOf) {
            List<VectorCondition> list = new ObjectArrayList<>(allOf.conditions().size());
            for (SurfaceRules.ConditionSource cond : allOf.conditions()) {
                list.add(VectorRuleCompiler.compileCondition(cond));
            }
            return new VectorAllOfCondition(list.toArray(new VectorCondition[0]));
        }

        if (conditionSource instanceof TagFilledCondition tagFilled) {
            boolean isFilled = tagFilled.rules().size() > 0;
            return (activeMask, ctx) -> {
                if (!isFilled) {
                    activeMask.clear();
                }

            };
        }

        return null;
    }
}
