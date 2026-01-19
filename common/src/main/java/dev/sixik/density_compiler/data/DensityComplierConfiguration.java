package dev.sixik.density_compiler.data;

import dev.sixik.density_compiler.instatiates.DensityInstantiate;

public record DensityComplierConfiguration(
        String className,
        String classSimpleName,
        boolean dumpsData,
        DensityInstantiate instantiate,
        String... interfaces_names
) {
}
