package dev.sixik.generator_accelerator.common.surface_compiler.callout;

@FunctionalInterface
public interface SurfaceScalarPredicate {
    boolean test(int x, int y, int z, SurfaceCalloutScratch scratch);
}
