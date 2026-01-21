package dev.sixik.density_compiler.instatiates;

import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.loaders.DynamicClassLoader;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.reflect.Constructor;

public class BasicDensityInstantiate implements DensityInstantiate {

    @Override
    public DensityFunction newInstance(
            DensityCompiler compiler,
            DynamicClassLoader loader,
            String className,
            String formatedClassName,
            byte[] bytes,
            Object... args
    ) {
        try {
            Class<?> clazz = loader.define(formatedClassName, bytes);

            try {
                clazz.getField("ORIGINAL_ROOT").set(null, compiler.getCurrentNode());
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject codec into generated class", e);
            }

            if (args.length == 0) {
                Constructor<?> constructor = clazz.getConstructor();
                return (DensityFunction) constructor.newInstance();
            } else {
                Constructor<?> constructor = clazz.getConstructor(Object[].class);
                return (DensityFunction) constructor.newInstance(args);
            }
        } catch (Exception e) {

            compiler.stackMachine.printDebug();
            throw new RuntimeException("Failed to instantiate compiled density function: " + className, e);
        }
    }
}
