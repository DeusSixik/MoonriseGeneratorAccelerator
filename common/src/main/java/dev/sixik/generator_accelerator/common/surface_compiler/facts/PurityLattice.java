package dev.sixik.generator_accelerator.common.surface_compiler.facts;

import dev.sixik.generator_accelerator.common.surface_compiler.ir.SurfaceEffect;

public final class PurityLattice {
    public boolean mayReorder(SurfaceEffect effect) {
        return effect != null && effect.mayReorder();
    }

    public boolean requiresStateToken(SurfaceEffect effect) {
        return effect != null && !effect.mayReorder();
    }

    public boolean unsafe(SurfaceEffect effect) {
        return effect == SurfaceEffect.UNSAFE || effect == SurfaceEffect.MUTATING || effect == SurfaceEffect.OPAQUE_CALLOUT;
    }
}
