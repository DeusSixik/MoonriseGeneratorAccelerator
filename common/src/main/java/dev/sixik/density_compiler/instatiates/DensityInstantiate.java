package dev.sixik.density_compiler.instatiates;

import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.loaders.DynamicClassLoader;
import net.minecraft.world.level.levelgen.DensityFunction;

public interface DensityInstantiate {

    DensityFunction newInstance(
            DensityCompiler compiler,
            DynamicClassLoader classLoader,
            String className,
            String formatedClassName,
            byte[] bytes,
            Object... args
    );
}
