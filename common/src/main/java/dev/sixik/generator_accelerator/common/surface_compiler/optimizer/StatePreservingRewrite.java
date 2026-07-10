package dev.sixik.generator_accelerator.common.surface_compiler.optimizer;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.StateTraceValidator;

public interface StatePreservingRewrite {
    SurfaceProgramIr apply(SurfaceProgramIr ir);

    default boolean preservesStateTrace(SurfaceProgramIr before, SurfaceProgramIr after) {
        return new StateTraceValidator().sameStateTrace(before, after);
    }
}
