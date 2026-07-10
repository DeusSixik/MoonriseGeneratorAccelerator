package dev.sixik.generator_accelerator.common.surface_compiler.validate;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.semantic.BaselineSurfaceInterpreter;
import net.minecraft.world.level.levelgen.SurfaceRules;

public final class VanillaParityComparator {
    private final BaselineSurfaceInterpreter oracle = new BaselineSurfaceInterpreter();
    private final StateTraceValidator traces = new StateTraceValidator();

    public boolean equivalent(SurfaceRules.RuleSource oracleRule, SurfaceProgramIr candidate) {
        return oracleRule != null && equivalent(this.oracle.debugIr(oracleRule), candidate);
    }

    public boolean equivalent(SurfaceProgramIr oracle, SurfaceProgramIr candidate) {
        return oracle != null
                && candidate != null
                && oracle.root().kind() == candidate.root().kind()
                && oracle.root().nodeCount() == candidate.root().nodeCount()
                && oracle.ops().size() == candidate.ops().size()
                && oracle.ops().equals(candidate.ops())
                && this.traces.sameStateTrace(oracle, candidate);
    }
}
