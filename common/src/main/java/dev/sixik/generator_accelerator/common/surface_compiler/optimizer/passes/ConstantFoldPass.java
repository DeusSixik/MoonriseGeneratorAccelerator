package dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.StatePreservingRewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative fold pass. Surface state constants are already represented as
 * pure STATE ops by the frontend, so this pass preserves the IR until more
 * value-level rewrite metadata is available.
 */
public final class ConstantFoldPass implements StatePreservingRewrite {
    @Override
    public SurfaceProgramIr apply(SurfaceProgramIr ir) {
        List<SurfaceOp> out = new ArrayList<>(ir.ops().size());
        for (SurfaceOp op : ir.ops()) {
            if (op.effect() == SurfaceEffect.PURE && !op.isStateful() && isEmptySequenceMarker(op)) {
                continue;
            }
            out.add(op);
        }
        return out.size() == ir.ops().size() ? ir : ir.copyWithOps(out);
    }

    private static boolean isEmptySequenceMarker(SurfaceOp op) {
        return "SEQUENCE".equals(op.opcode()) && "children=0".equals(op.detail());
    }
}
