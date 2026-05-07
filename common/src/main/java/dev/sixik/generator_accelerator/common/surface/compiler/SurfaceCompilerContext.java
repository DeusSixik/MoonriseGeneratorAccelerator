package dev.sixik.generator_accelerator.common.surface.compiler;

import net.minecraft.world.level.levelgen.SurfaceRules;

interface SurfaceCompilerContext {
    SurfaceRuleNode compileRule(SurfaceRules.RuleSource ruleSource);

    SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource);
}
