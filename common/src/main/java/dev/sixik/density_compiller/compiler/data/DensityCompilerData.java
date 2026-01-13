package dev.sixik.density_compiller.compiler.data;

import dev.sixik.density_compiller.compiler.tasks.*;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.Map;
import java.util.function.Supplier;

public class DensityCompilerData {

    private static boolean isLoaded = false;

    private static final Map<Class<?>, Supplier<DensityCompilerTask<?>>> REGISTRY = new Reference2ObjectOpenHashMap<>();
    private static final Map<Class<?>, Supplier<DensityCompilerTask<?>>> CACHE = new Reference2ObjectOpenHashMap<>();

    public static void boot() {
        if(isLoaded) return;

        register(DensityFunctions.Constant.class, DensityCompilerConstantTask::new);
        register(DensityFunctions.TwoArgumentSimpleFunction.class, DensityCompilerTwoArgumentSimpleFunctionTask::new);
        register(DensityFunctions.BlendAlpha.class, DensityCompilerBlendAlphaTask::new);
        register(DensityFunctions.BlendOffset.class, DensityCompilerBlendOffsetTask::new);
        register(DensityFunctions.BeardifierMarker.class, DensityCompilerBeardifierMarkerTask::new);
        register(DensityFunctions.ShiftedNoise.class, DensityCompilerShiftedNoiseTask::new);
        register(DensityFunctions.RangeChoice.class, DensityCompilerRangeChoiceTask::new);
        register(DensityFunctions.ShiftA.class, DensityCompilerShiftATask::new);
        register(DensityFunctions.ShiftB.class, DensityCompilerShiftBTask::new);
        register(DensityFunctions.Shift.class, DensityCompilerShiftTask::new);
        register(DensityFunctions.Clamp.class, DensityCompilerClampTask::new);
        register(DensityFunctions.Mapped.class, DensityCompilerMappedTask::new);

        /*
            Custom
         */
        register(DensitySpecializations.FastAdd.class, DensityCompilerFastAddTask::new);
        register(DensitySpecializations.FastMul.class, DensityCompilerFastMulTask::new);
        register(DensitySpecializations.FastMin.class, DensityCompilerFastMinTask::new);
        register(DensitySpecializations.FastMax.class, DensityCompilerFastMaxTask::new);


        isLoaded = true;
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

        /*
            If there's an exact match, we'll take it right away.
         */
        if (REGISTRY.containsKey(target)) {
            return REGISTRY.get(target);
        }

        /*
            Otherwise, we are looking for the "nearest" parent.
         */
        Class<?> bestCandidate = null;

        for (Class<?> registered : REGISTRY.keySet()) {

            /*
                Checking whether target is the registered heir (target instanceof registered)
             */
            if (registered.isAssignableFrom(target)) {

                /*
                    If we haven't found a candidate yet, or the new candidate
                    is "closer" (more specific) than the previous one.
                 */
                if (bestCandidate == null || bestCandidate.isAssignableFrom(registered)) {
                    bestCandidate = registered;
                }
            }
        }

        return bestCandidate != null ? REGISTRY.get(bestCandidate) : null;
    }
}
