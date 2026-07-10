package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

public final class StateEffectAnalyzer {
    private final PurityLattice purity = new PurityLattice();

    public StateEffectSummary analyze(SurfaceProgramIr ir) {
        int ordered = 0;
        int unsafe = 0;
        for (SurfaceOp op : ir.ops()) {
            if (this.purity.requiresStateToken(op.effect())) {
                ordered++;
            }
            if (this.purity.unsafe(op.effect())) {
                unsafe++;
            }
        }
        return new StateEffectSummary(ordered, unsafe, ir.tokenChainIsLinear());
    }

    public record StateEffectSummary(int orderedOps, int unsafeOps, boolean linearTokens) {
    }
}
