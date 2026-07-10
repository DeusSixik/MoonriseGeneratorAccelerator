package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;

public record LegacySurfaceAdapter(String id, SurfaceScalarPredicate predicate) implements SurfacePredicateFactory {
    @Override
    public SurfacePredicateDescriptor descriptor() {
        return new SurfacePredicateDescriptor(this.id, AdapterSafetyClass.READ_ONLY_LEGACY_BLOCKPOS, false, true);
    }

    @Override
    public SurfaceScalarPredicate create() {
        return this.predicate;
    }
}
