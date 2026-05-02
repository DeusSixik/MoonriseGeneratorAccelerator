package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone;

import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsRuleSource;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BetweenRepeatingNoiseRange;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource;
import org.jetbrains.annotations.Nullable;

public class BiomesWeveGone$CompilerData {



    @Nullable
    public static VectorRule compileRule(SurfaceRules.RuleSource ruleSource) {
        if (ruleSource instanceof BandsRuleSource bandsRule) {
            return new VectorBandsRule(bandsRule);
        }

        if (ruleSource instanceof WeightedRuleSource weightedRule) {
            return new VectorWeightedRule(weightedRule);
        }

        if (ruleSource instanceof BetweenRepeatingNoiseRange repeatingRange) {
            try {
                java.lang.reflect.Field ruleField = BetweenRepeatingNoiseRange.class.getDeclaredField("rule");
                ruleField.setAccessible(true);
                SurfaceRules.RuleSource innerRule = (SurfaceRules.RuleSource) ruleField.get(repeatingRange);
                return VectorRuleCompiler.compileRule(innerRule);
            } catch (Exception e) {
                throw new RuntimeException("Failed to compile BetweenRepeatingNoiseRange", e);
            }
        }

        return null;
    }

    @Nullable
    public static VectorCondition compileCondition(SurfaceRules.ConditionSource conditionSource) {
        throw new UnsupportedOperationException();
    }
}
