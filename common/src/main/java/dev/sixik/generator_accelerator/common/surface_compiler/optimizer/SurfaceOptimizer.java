package dev.sixik.generator_accelerator.common.surface_compiler.optimizer;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes.BranchFusionPass;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes.ConstantFoldPass;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes.CsePass;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes.MaskNarrowingPass;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes.YBandLoweringPass;

import java.util.List;

public final class SurfaceOptimizer {
    private final List<StatePreservingRewrite> passes = List.of(
            new ConstantFoldPass(),
            new CsePass(),
            new MaskNarrowingPass(),
            new YBandLoweringPass(),
            new BranchFusionPass()
    );

    public SurfaceProgramIr optimize(SurfaceProgramIr ir) {
        SurfaceProgramIr current = ir;
        for (StatePreservingRewrite pass : this.passes) {
            SurfaceProgramIr next = pass.apply(current);
            if (!pass.preservesStateTrace(current, next)) {
                return current;
            }
            current = next;
        }
        return current;
    }
}
