package dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.StatePreservingRewrite;

import java.util.ArrayList;
import java.util.List;

/** Removes redundant pure mask bounds while keeping ordered/stateful operations intact. */
public final class MaskNarrowingPass implements StatePreservingRewrite {
    @Override
    public SurfaceProgramIr apply(SurfaceProgramIr ir) {
        List<SurfaceOp> out = new ArrayList<>(ir.ops().size());
        SurfaceOp previousPureBand = null;
        for (SurfaceOp op : ir.ops()) {
            if (isPureBandMask(op)) {
                if (previousPureBand != null && previousPureBand.detail().equals(op.detail())) {
                    continue;
                }
                previousPureBand = op;
            } else if (op.isStateful() || op.effect() != SurfaceEffect.PURE) {
                previousPureBand = null;
            }
            out.add(op);
        }
        return out.size() == ir.ops().size() ? ir : ir.copyWithOps(out);
    }

    private static boolean isPureBandMask(SurfaceOp op) {
        return op.effect() == SurfaceEffect.PURE && !op.isStateful() && op.domain() == SurfaceDomain.Y_BAND;
    }
}
