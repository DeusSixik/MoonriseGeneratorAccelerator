package dev.sixik.density_compiller.compiler;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;

public class CompilerInfrastructure {

    /**
     * Custom loader to turn byte[] into Class<?>
     */
    private static final DynamicClassLoader LOADER = new DynamicClassLoader(CompilerInfrastructure.class.getClassLoader());

    public static DensityFunction defineAndInstantiate(String className, byte[] bytes, List<Object> leaves) {
        try {
            Class<?> clazz = LOADER.define(className.replace('/', '.'), bytes);

            // Ищем конструктор, принимающий Object[], так как в байт-коде мы прописали ([Ljava/lang/Object;)V
            Constructor<?> constructor = clazz.getConstructor(Object[].class);

            // Передаем массив напрямую
            return (DensityFunction) constructor.newInstance((Object) leaves.toArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate compiled density function: " + className, e);
        }
    }

    public static void debugWriteClass(String filename, byte[] bytes) {
        try (FileOutputStream fos = new FileOutputStream("compiler/" + filename)) {
            fos.write(bytes);
            System.out.println("Class dumped to: " + new java.io.File(filename).getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * A simple ClassLoader with access to defineClass
     */
    static class DynamicClassLoader extends ClassLoader {
        protected DynamicClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> define(String name, byte[] data) {
            return defineClass(name, data, 0, data.length);
        }
    }
}
