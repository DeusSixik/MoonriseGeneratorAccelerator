package dev.sixik.density_compiller.compiler.data;

import dev.sixik.density_compiller.compiler.tasks.*;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

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
        register(DensityFunctions.MulOrAdd.class, DensityCompilerMulOrAddTask::new);
        register(DensityFunctions.Noise.class, DensityCompilerNoiseTask::new);
//        register(DensityFunctions.WeirdScaledSampler.class, DensityCompilerWeirdScaledSamplerTask::new);
        register(DensityFunctions.BlendDensity.class, DensityCompilerBlendDensityTask::new);
        register(DensityFunctions.YClampedGradient.class, DensityCompilerYClampedGradientTask::new);
        register(DensityFunctions.HolderHolder.class, DensityCompilerHolderHolderTask::new);
        register(DensityFunctions.Marker.class, DensityCompilerMarkerTask::new);
        register(DensityFunctions.EndIslandDensityFunction.class, DensityCompilerEndIslandTask::new);
        register(DensityFunctions.Ap2.class, DensityCompilerAp2Task::new);
//
        register(NoiseChunk.FlatCache.class, DensityCompilerFlatCacheTask::new);
        register(NoiseChunk.Cache2D.class, DensityCompilerCache2DTask::new);
        register(NoiseChunk.NoiseInterpolator.class, DensityCompilerNoiseInterpolatorTask::new);

        /*
            Custom
         */
//        register(DensitySpecializations.FastAdd.class, DensityCompilerFastAddTask::new);
//        register(DensitySpecializations.FastMul.class, DensityCompilerFastMulTask::new);
//        register(DensitySpecializations.FastMin.class, DensityCompilerFastMinTask::new);
//        register(DensitySpecializations.FastMax.class, DensityCompilerFastMaxTask::new);
//        register(DensityFunctionSplineWrapper.class, DensityCompilerDensityFunctionSplineWrapperTask::new);

//        register(DensityFunctions.Spline.class, DensityCompilerSplineTask::new);

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
