package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SurfaceFactsAnalyzer {
    private final PurityLattice purity = new PurityLattice();
    private final StateEffectAnalyzer stateEffects = new StateEffectAnalyzer();
    private final SnapshotPlanner snapshots = new SnapshotPlanner();
    private final HaloAnalyzer halo = new HaloAnalyzer();

    public SurfaceFacts analyze(SurfaceProgramIr ir) {
        boolean hasOpaque = false;
        boolean hasUnsafeMutation = false;
        boolean hasStateTokens = false;
        Set<String> domains = new LinkedHashSet<>();
        StateEffectAnalyzer.StateEffectSummary effects = this.stateEffects.analyze(ir);

        for (SurfaceOp op : ir.ops()) {
            domains.add(op.domain().name());
            hasOpaque |= this.purity.unsafe(op.effect()) && op.domain() == SurfaceDomain.OPAQUE;
            hasUnsafeMutation |= this.purity.unsafe(op.effect());
            if (op.isStateful()) {
                hasStateTokens = true;
            }
        }

        boolean safeForInterpreter = !hasOpaque && !hasUnsafeMutation && effects.linearTokens();
        boolean safeForHybrid = safeForInterpreter && !domains.contains(SurfaceDomain.HALO.name());
        boolean directWriteCertified = safeForInterpreter && !hasStateTokens && !hasOpaque && ir.ops().stream().allMatch(op -> this.purity.mayReorder(op.effect()));
        return new SurfaceFacts(
                safeForInterpreter,
                safeForHybrid,
                directWriteCertified,
                hasOpaque,
                hasStateTokens,
                safeForInterpreter,
                hasStateTokens,
                ir.ops().size(),
                effects.orderedOps(),
                Set.copyOf(domains),
                this.snapshots.plan(ir),
                this.halo.analyze(ir)
        );
    }
}
