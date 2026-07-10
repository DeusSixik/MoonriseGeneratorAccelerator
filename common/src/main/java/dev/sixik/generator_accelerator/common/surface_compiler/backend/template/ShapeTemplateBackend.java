package dev.sixik.generator_accelerator.common.surface_compiler.backend.template;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative shape classifier used before bytecode generation is allowed. */
public final class ShapeTemplateBackend {
    public ShapeTemplate describe(SurfaceProgramIr ir) {
        Map<SurfaceEffect, Integer> effects = new LinkedHashMap<>();
        int stateful = 0;
        for (SurfaceOp op : ir.ops()) {
            effects.merge(op.effect(), 1, Integer::sum);
            if (op.isStateful()) {
                stateful++;
            }
        }
        return new ShapeTemplate(ir.rootClassName(), ir.nodeCount(), ir.ops().size(), stateful, Map.copyOf(effects));
    }

    public boolean canUseDirectTemplate(SurfaceProgramIr ir) {
        return directConstantState(ir) != null;
    }

    public net.minecraft.world.level.block.state.BlockState directConstantState(SurfaceProgramIr ir) {
        if (ir == null || !ir.tokenChainIsLinear() || ir.hasUnsafeOrMutatingOp()) {
            return null;
        }
        if (!ir.ops().stream().allMatch(op -> op.effect() == SurfaceEffect.PURE || op.effect() == SurfaceEffect.READ_ONLY_STABLE)) {
            return null;
        }
        return unconditionalConstantState(ir.root());
    }

    private static net.minecraft.world.level.block.state.BlockState unconditionalConstantState(SurfaceNode node) {
        if (node == null) {
            return null;
        }
        if (node.kind() == SurfaceNode.Kind.STATE) {
            return node.blockState();
        }
        return null;
    }

    public record ShapeTemplate(String rootClassName, int nodeCount, int opCount, int statefulOpCount, Map<SurfaceEffect, Integer> effects) {
    }
}
