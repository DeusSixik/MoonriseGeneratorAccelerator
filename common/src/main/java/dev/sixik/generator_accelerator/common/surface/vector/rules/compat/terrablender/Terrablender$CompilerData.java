package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.terrablender;

import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;

import java.util.HashMap;
import java.util.Map;

public class Terrablender$CompilerData {



    @Nullable
    public static VectorRule compileRule(SurfaceRules.RuleSource ruleSource) {

        if (ruleSource instanceof NamespacedSurfaceRuleSource namespacedRule) {
            VectorRule compiledBase = VectorRuleCompiler.compileRule(namespacedRule.base());

            Map<String, VectorRule> compiledSources = new HashMap<>();
            for (Map.Entry<String, SurfaceRules.RuleSource> entry : namespacedRule.sources().entrySet()) {
                compiledSources.put(entry.getKey(), VectorRuleCompiler.compileRule(entry.getValue()));
            }

            return new VectorNamespacedRule(compiledBase, compiledSources);
        }

        return null;
    }

    @Nullable
    public static VectorCondition compileCondition(SurfaceRules.ConditionSource conditionSource) {
        throw new UnsupportedOperationException();
    }
}
