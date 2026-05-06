package dev.sixik.generator_accelerator.common.density.compiler.compiler;

/**
 * Fully-qualified names of vanilla {@code DensityFunction} implementation classes that
 * are not public from our package (e.g. {@code protected} nested types in
 * {@link net.minecraft.world.level.levelgen.DensityFunctions}).
 */
public final class McDensityFunctionClassNames {
    private McDensityFunctionClassNames() {}

    /**
     * {@link net.minecraft.world.level.levelgen.DensityFunctions.EndIslandDensityFunction}
     * — protected nested class; use only for {@code getClass().getName()} checks.
     */
    public static final String DENSITY_FUNCTIONS_END_ISLAND =
            "net.minecraft.world.level.levelgen.DensityFunctions$EndIslandDensityFunction";
}
