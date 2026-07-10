package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceOp;
import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceProgramIr;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SnapshotPlan;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SnapshotPlanner {
    public SnapshotPlan plan(SurfaceProgramIr ir) {
        Set<String> domains = new LinkedHashSet<>();
        boolean ordered = false;
        for (SurfaceOp op : ir.ops()) {
            domains.add(op.domain().name());
            ordered |= op.isStateful();
        }
        return SnapshotPlan.forDomains(domains, ordered);
    }
}
