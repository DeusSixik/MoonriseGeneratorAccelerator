package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;

record SurfaceIRCompileResult(
        SurfaceProgram program,
        SurfaceRuleIR ir,
        String rootNodeName,
        int compiledConditionCount,
        int conditionCacheSlots,
        int fallbackRuleCount,
        int fallbackConditionCount
) {
}
