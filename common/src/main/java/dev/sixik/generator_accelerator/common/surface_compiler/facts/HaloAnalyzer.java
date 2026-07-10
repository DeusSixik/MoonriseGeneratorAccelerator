package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.halo.HaloPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

public final class HaloAnalyzer {
    public HaloPlan analyze(SurfaceProgramIr ir) {
        for (SurfaceOp op : ir.ops()) {
            if (op.domain() == SurfaceDomain.HALO) {
                return HaloPlan.required(1, 0, 1);
            }
        }
        return HaloPlan.none();
    }
}
