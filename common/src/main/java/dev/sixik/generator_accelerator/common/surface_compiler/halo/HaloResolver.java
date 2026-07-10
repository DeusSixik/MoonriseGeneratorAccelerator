package dev.sixik.generator_accelerator.common.surface_compiler.halo;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceReadView;

public final class HaloResolver {
    public NonBlockingNeighborView resolve(HaloPlan plan, SurfaceReadView local, boolean neighborReady) {
        if (plan == null || !plan.required()) {
            return new NonBlockingNeighborView(local, true);
        }
        return new NonBlockingNeighborView(local, neighborReady);
    }
}
