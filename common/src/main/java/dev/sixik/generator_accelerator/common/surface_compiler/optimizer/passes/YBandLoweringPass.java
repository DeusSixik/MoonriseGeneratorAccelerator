package dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.StatePreservingRewrite;

import java.util.ArrayList;
import java.util.List;

/** Lowers duplicate stable Y-band reads to a single column prepass marker. */
public final class YBandLoweringPass implements StatePreservingRewrite {
    @Override
    public SurfaceProgramIr apply(SurfaceProgramIr ir) {
        List<SurfaceOp> out = new ArrayList<>(ir.ops().size());
        boolean emittedStableYBand = false;
        for (SurfaceOp op : ir.ops()) {
            if (isStableYBand(op)) {
                if (emittedStableYBand) {
                    continue;
                }
                emittedStableYBand = true;
            } else if (op.isStateful() || op.effect() == SurfaceEffect.READ_ONLY_ORDERED) {
                emittedStableYBand = false;
            }
            out.add(op);
        }
        return out.size() == ir.ops().size() ? ir : ir.copyWithOps(out);
    }

    private static boolean isStableYBand(SurfaceOp op) {
        return op.effect() == SurfaceEffect.READ_ONLY_STABLE && !op.isStateful() && op.domain() == SurfaceDomain.Y_BAND;
    }
}
