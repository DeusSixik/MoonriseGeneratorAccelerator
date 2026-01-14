package dev.sixik.density_compiller.compiler.pipeline.configuration;

import dev.sixik.density_compiller.compiler.pipeline.instatiates.DensityInstantiate;

public record DensityCompilerPipelineConfigurator(
        String className,
        String classSimpleName,
        boolean dumpsData,
        DensityInstantiate instantiate,
        String... interfaces_names
) {
}
