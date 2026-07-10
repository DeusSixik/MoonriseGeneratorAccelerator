package dev.sixik.generator_accelerator.common.surface_compiler.frontend;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceNode;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceStateToken;

public final class SurfaceIrBuilder {
    public SurfaceProgramIr build(SurfaceRuleScanner.SurfaceScanResult scan) {
        SurfaceProgramIr ir = new SurfaceProgramIr(scan.rootClassName(), scan.root());
        TokenCursor cursor = new TokenCursor();
        emit(scan.root(), ir, cursor);
        return ir;
    }

    private void emit(SurfaceNode node, SurfaceProgramIr ir, TokenCursor cursor) {
        SurfaceEffect effect = node.effect();
        SurfaceStateToken in = null;
        SurfaceStateToken out = null;
        if (!effect.mayReorder()) {
            in = cursor.current;
            out = cursor.current.next();
            cursor.current = out;
        }
        ir.add(new SurfaceOp(node.opcode(), effect, node.domain() == null ? SurfaceDomain.OPAQUE : node.domain(), in, out, node.detail()));
        for (SurfaceNode child : node.children()) {
            emit(child, ir, cursor);
        }
    }

    private static final class TokenCursor {
        private SurfaceStateToken current = SurfaceStateToken.initial();
    }
}
