package dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.StatePreservingRewrite;

import java.util.ArrayList;
import java.util.List;

/** Fuses adjacent pure duplicate branch markers only. */
public final class BranchFusionPass implements StatePreservingRewrite {
    @Override
    public SurfaceProgramIr apply(SurfaceProgramIr ir) {
        List<SurfaceOp> out = new ArrayList<>(ir.ops().size());
        SurfaceOp previous = null;
        for (SurfaceOp op : ir.ops()) {
            if (isPureBranch(op) && previous != null && previous.equals(op)) {
                continue;
            }
            out.add(op);
            previous = op.isStateful() ? null : op;
        }
        return out.size() == ir.ops().size() ? ir : ir.copyWithOps(out);
    }

    private static boolean isPureBranch(SurfaceOp op) {
        return op.effect() == SurfaceEffect.PURE && !op.isStateful() && ("IF_TRUE".equals(op.opcode()) || "SEQUENCE".equals(op.opcode()));
    }
}
