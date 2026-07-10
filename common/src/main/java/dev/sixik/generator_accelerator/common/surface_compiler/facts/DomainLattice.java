package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceDomain;

public final class DomainLattice {
    public boolean includes(SurfaceDomain wider, SurfaceDomain narrower) {
        if (wider == narrower) {
            return true;
        }
        return wider == SurfaceDomain.OPAQUE || wider == SurfaceDomain.MUTATION;
    }

    public boolean conflicts(SurfaceDomain left, SurfaceDomain right) {
        return left == SurfaceDomain.MUTATION || right == SurfaceDomain.MUTATION
                || left == SurfaceDomain.OPAQUE || right == SurfaceDomain.OPAQUE;
    }
}
