package dev.sixik.density_compiler.data;

import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import dev.sixik.density_compiler.tasks.DensityCompilerConstantTask;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.Map;
import java.util.function.Supplier;

public class DensityCompilerData {

    private static boolean isLoaded = false;

    private static final Map<Class<?>, Supplier<DensityCompilerTask<?>>> REGISTRY = new Reference2ObjectOpenHashMap<>();
    private static final Map<Class<?>, Supplier<DensityCompilerTask<?>>> CACHE = new Reference2ObjectOpenHashMap<>();

    public static void bootImpl() {
        if(isLoaded) return;
        boot();
        isLoaded = true;
    }

    protected static void boot() {
        register(DensityFunctions.Constant.class, DensityCompilerConstantTask::new);
    }

    public static void register(Class<? extends DensityFunction> clz, Supplier<DensityCompilerTask<?>> supplier) {
        REGISTRY.put(clz, supplier);
        CACHE.clear();
    }

    /**
     * Searches for a task for a specific class.
     * If there is no direct match, it searches for the nearest registered parent or interface.
     */
    @SuppressWarnings("unchecked")
    public static Supplier<DensityCompilerTask<?>> getTask(Class<?> targetClass) {
        /*
            Quick return from the cache
         */
        if (CACHE.containsKey(targetClass)) {
            return CACHE.get(targetClass);
        }

        /*
            Search
         */
        final Supplier<DensityCompilerTask<?>> found = findBestMatch(targetClass);
        CACHE.put(targetClass, found);
        return found;
    }

    private static Supplier<DensityCompilerTask<?>> findBestMatch(Class<?> target) {

        if (REGISTRY.containsKey(target)) {
            return REGISTRY.get(target);
        }

        /*
            If there is no direct match, we search by hierarchy.
         */
        Class<?> bestCandidate = null;
        for (Class<?> registered : REGISTRY.keySet()) {
            if (registered.isAssignableFrom(target)) {
                if (bestCandidate == null || bestCandidate.isAssignableFrom(registered)) {
                    bestCandidate = registered;
                }
            }
        }

        /*
            If you still haven't found it, we check by name (for HolderHolder and other private records)
         */
        if (bestCandidate == null) {
            String targetName = target.getSimpleName();
            for (Map.Entry<Class<?>, Supplier<DensityCompilerTask<?>>> entry : REGISTRY.entrySet()) {
                if (entry.getKey().getSimpleName().equals(targetName)) {
                    return entry.getValue();
                }
            }
        }

        return bestCandidate != null ? REGISTRY.get(bestCandidate) : null;
    }
}
