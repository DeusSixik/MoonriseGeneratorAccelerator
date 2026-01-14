package dev.sixik.density_compiller.compiler.pipeline.instatiates;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.loaders.DynamicClassLoader;
import net.minecraft.world.level.levelgen.DensityFunction;

public interface DensityInstantiate {

    DensityFunction newInstance(
            DensityCompilerPipeline pipeline,
            DynamicClassLoader classLoader,
            String className,
            String formatedClassName,
            byte[] bytes,
            Object... args
    );
}
