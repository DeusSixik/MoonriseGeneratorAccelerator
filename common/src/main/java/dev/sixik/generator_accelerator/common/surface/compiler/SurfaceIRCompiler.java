package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Map;

final class SurfaceIRCompiler {
    private SurfaceIRCompiler() {
    }

    static SurfaceIRCompileResult compile(SurfaceRules.RuleSource ruleSource) {
        if (ruleSource instanceof SurfaceRules.BlockRuleSource blockRule) {
            int blockId = Block.getId(blockRule.resultState());
            boolean mayWriteFluid = !blockRule.resultState().getFluidState().isEmpty();
            SurfaceRuleIR.Block ir = new SurfaceRuleIR.Block(blockId, mayWriteFluid);
            SurfaceProgram program = new SurfaceProgram(
                    new int[]{SurfaceProgram.OP_BLOCK},
                    new int[]{blockId},
                    new Object[1],
                    0,
                    0,
                    mayWriteFluid
            );
            return new SurfaceIRCompileResult(program, ir, "surface-ir-direct", 0, 0, 0, 0);
        }

        SurfaceIRBuilder builder = new SurfaceIRBuilder();
        SurfaceRuleIR ir = builder.buildRule(ruleSource);
        Map<dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceConditionIR, Integer> conditionUseCounts = builder.conditionUseCounts();
        if (SurfaceCompilerConfig.DAG && builder.fullOptimizerCandidate()) {
            SurfaceIROptimizer.Result optimized = SurfaceIROptimizer.optimize(ir);
            ir = optimized.ir();
            conditionUseCounts = optimized.conditionUseCounts();
        }
        SurfaceIRLowerer lowerer = new SurfaceIRLowerer(conditionUseCounts);
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
