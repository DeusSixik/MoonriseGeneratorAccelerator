package dev.sixik.generator_accelerator.common.surface.compiler;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceIRAnalyzer;
import dev.sixik.generator_accelerator.common.surface.compiler.ir.SurfaceRuleIR;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.slf4j.Logger;

import java.util.List;

final class SurfacePlanDump {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SurfacePlanDump() {
    }

    static void compiled(
            SurfaceRules.RuleSource source,
            SurfaceProgram program,
            String rootNode,
            int identityConditionCount,
            int structuralConditionCount,
            int conditionCacheSlots,
            int fallbackIslandCount,
            List<String> fallbackRuleClasses,
            List<String> fallbackConditionClasses
    ) {
        if (!SurfaceCompilerConfig.DUMP) {
            return;
        }

        LOGGER.info(
                "GA Surface compiler dump: source={}, backend=interpreted-mask4096, root={}, opcodes={}, blockOps={}, testBlockOps={}, genericRuleOps={}, requirements=0x{}, mayWriteFluid={}, identityConditions={}, structuralConditions={}, conditionCacheSlots={}, fallbackIslands={}, fallbackRules={}, fallbackConditions={}, flags[ir={}, dag={}, columnInterval={}]",
                source.getClass().getName(),
                rootNode,
                program.opcodeCount(),
                program.blockOpcodeCount(),
                program.testBlockOpcodeCount(),
                program.genericRuleOpcodeCount(),
                Integer.toHexString(program.requirements()),
                program.mayWriteFluid(),
                identityConditionCount,
                structuralConditionCount,
                conditionCacheSlots,
                fallbackIslandCount,
                fallbackRuleClasses,
                fallbackConditionClasses,
                SurfaceCompilerConfig.IR,
                SurfaceCompilerConfig.DAG,
                SurfaceCompilerConfig.COLUMN_INTERVAL
        );
    }

    static void compiledIr(
            SurfaceRules.RuleSource source,
            SurfaceRuleIR ir,
            SurfaceProgram program,
            String rootNode,
            int compiledConditionCount,
            int conditionCacheSlots
    ) {
        if (!SurfaceCompilerConfig.DUMP) {
            return;
        }

        LOGGER.info(
                "GA Surface compiler dump: source={}, backend=surface-ir-phase3, root={}, irRules={}, irConditions={}, irFallbackRules={}, irFallbackConditions={}, opcodes={}, blockOps={}, testBlockOps={}, genericRuleOps={}, requirements=0x{}, irRequirements=0x{}, mayWriteFluid={}, irMayWriteFluid={}, compiledConditions={}, conditionCacheSlots={}, flags[ir={}, dag={}, columnInterval={}]",
                source.getClass().getName(),
                rootNode,
                SurfaceIRAnalyzer.ruleCount(ir),
                SurfaceIRAnalyzer.conditionCount(ir),
                SurfaceIRAnalyzer.fallbackRuleCount(ir),
                SurfaceIRAnalyzer.fallbackConditionCount(ir),
                program.opcodeCount(),
                program.blockOpcodeCount(),
                program.testBlockOpcodeCount(),
                program.genericRuleOpcodeCount(),
                Integer.toHexString(program.requirements()),
                Integer.toHexString(SurfaceIRAnalyzer.requirements(ir)),
                program.mayWriteFluid(),
                SurfaceIRAnalyzer.mayWriteFluid(ir),
                compiledConditionCount,
                conditionCacheSlots,
                SurfaceCompilerConfig.IR,
                SurfaceCompilerConfig.DAG,
                SurfaceCompilerConfig.COLUMN_INTERVAL
        );
    }
}
