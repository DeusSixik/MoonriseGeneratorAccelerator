package dev.sixik.density_compiller.compiler.pipeline.instatiates;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.loaders.DynamicClassLoader;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.reflect.Constructor;

public class BasicDensityInstantiate implements DensityInstantiate {

    @Override
    public DensityFunction newInstance(
            DensityCompilerPipeline pipeline,
            DynamicClassLoader loader,
            String className,
            String formatedClassName,
            byte[] bytes,
            Object... args
    ) {
        try {
            /*
                Load class
             */
            Class<?> clazz = loader.define(formatedClassName, bytes);

            /*
                We are looking for a constructor that accepts an array of DensityFunction[] (our leaves)
             */
            Constructor<?> constructor = clazz.getConstructor(DensityFunction[].class);

            /*
                Creating an instance
             */
            return (DensityFunction) constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate compiled density function: " + className, e);
        }
    }
}
