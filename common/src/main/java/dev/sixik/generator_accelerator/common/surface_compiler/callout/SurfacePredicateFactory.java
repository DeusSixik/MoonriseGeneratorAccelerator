package dev.sixik.generator_accelerator.common.surface_compiler.callout;

public interface SurfacePredicateFactory {
    SurfacePredicateDescriptor descriptor();

    SurfaceScalarPredicate create();
}
