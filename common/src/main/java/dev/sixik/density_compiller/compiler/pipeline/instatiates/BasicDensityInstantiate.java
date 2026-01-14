package dev.sixik.density_compiller.compiler.pipeline.instatiates;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.loaders.DynamicClassLoader;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

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
            Class<?> clazz = loader.define(formatedClassName, bytes);

            System.out.println(args.length);

            if (args.length == 0) {
                Constructor<?> constructor = clazz.getConstructor();
                return (DensityFunction) constructor.newInstance();
            } else {
                Constructor<?> constructor = clazz.getConstructor(DensityFunction[].class);
                return (DensityFunction) constructor.newInstance(args);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate compiled density function: " + className, e);
        }
    }
}
