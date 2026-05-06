package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;
import net.minecraft.world.level.levelgen.SurfaceRules;

final class SurfaceIRCompiler {
    private SurfaceIRCompiler() {
    }

    static SurfaceIRCompileResult compile(SurfaceRules.RuleSource ruleSource) {
        SurfaceIRBuilder builder = new SurfaceIRBuilder();
        SurfaceRuleIR ir = builder.buildRule(ruleSource);
        SurfaceIRLowerer lowerer = new SurfaceIRLowerer(builder.conditionUseCounts());
        SurfaceIRLowerer.LoweredProgram lowered = lowerer.lowerProgram(ir, builder.fallbackRuleCount());
        return new SurfaceIRCompileResult(
                lowered.program(),
                ir,
                lowered.rootNodeName(),
                lowerer.compiledConditionCount(),
                lowerer.conditionCacheSlots(),
                builder.fallbackRuleCount(),
                builder.fallbackConditionCount()
        );
    }
}
