package dev.sixik.generator_accelerator.common.surface_compiler.optimizer.passes;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.optimizer.StatePreservingRewrite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure-op CSE that refuses to remove ordered/stateful operations. */
public final class CsePass implements StatePreservingRewrite {
    @Override
    public SurfaceProgramIr apply(SurfaceProgramIr ir) {
        List<SurfaceOp> out = new ArrayList<>(ir.ops().size());
        Set<String> seenReorderable = new HashSet<>();
        for (SurfaceOp op : ir.ops()) {
            if (isReorderableValue(op)) {
                String key = op.effect() + "|" + op.opcode() + '|' + op.domain() + '|' + op.detail();
                if (!seenReorderable.add(key)) {
                    continue;
                }
            }
            out.add(op);
        }
        return out.size() == ir.ops().size() ? ir : ir.copyWithOps(out);
    }

    private static boolean isReorderableValue(SurfaceOp op) {
        return !op.isStateful() && (op.effect() == SurfaceEffect.PURE || op.effect() == SurfaceEffect.READ_ONLY_STABLE);
    }
}
