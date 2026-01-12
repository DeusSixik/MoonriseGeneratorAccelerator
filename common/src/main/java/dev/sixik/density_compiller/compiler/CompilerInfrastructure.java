package dev.sixik.density_compiller.compiler;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;

public class CompilerInfrastructure {

    // Кастомный загрузчик, чтобы превращать byte[] в Class<?>
    private static final DynamicClassLoader LOADER = new DynamicClassLoader(CompilerInfrastructure.class.getClassLoader());

    public static DensityFunction defineAndInstantiate(String className, byte[] bytes, List<DensityFunction> leaves) {
        try {
            // 1. Загружаем класс
            Class<?> clazz = LOADER.define(className.replace('/', '.'), bytes);

            // 2. Ищем конструктор, который принимает массив DensityFunction[] (наши листья)
            Constructor<?> constructor = clazz.getConstructor(DensityFunction[].class);

            // 3. Создаем инстанс
            return (DensityFunction) constructor.newInstance((Object) leaves.toArray(new DensityFunction[0]));
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

    // Простой ClassLoader с доступом к defineClass
    static class DynamicClassLoader extends ClassLoader {
        protected DynamicClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> define(String name, byte[] data) {
            return defineClass(name, data, 0, data.length);
        }
    }
}
