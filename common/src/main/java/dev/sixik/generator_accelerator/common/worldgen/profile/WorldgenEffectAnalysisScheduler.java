package dev.sixik.generator_accelerator.common.worldgen.profile;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class WorldgenEffectAnalysisScheduler {
    public static final int DEFAULT_HOT_COST_THRESHOLD = 4;

    private WorldgenEffectAnalysisScheduler() {
    }

    public static CompletableFuture<WorldgenEffectProfile> scheduleHotUnit(
            Class<?> unitClass,
            String methodHint,
            int estimatedCost
    ) {
        if (estimatedCost < DEFAULT_HOT_COST_THRESHOLD) {
            return CompletableFuture.completedFuture(WorldgenEffectProfileCache.global().profile(unitClass, methodHint));
        }
        return GAScheduler.supplyAsync(
                GAScheduler.Lane.COMPILE,
                () -> WorldgenEffectProfileCache.global().profile(unitClass, methodHint)
        );
    }

    public static List<CompletableFuture<WorldgenEffectProfile>> scheduleHotUnits(Collection<WorldgenUnitProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        ArrayList<CompletableFuture<WorldgenEffectProfile>> futures = new ArrayList<>();
        for (WorldgenUnitProfile profile : profiles) {
            if (profile != null && profile.estimatedCost() >= DEFAULT_HOT_COST_THRESHOLD) {
                futures.add(scheduleByName(profile.className(), profile.entryPointMethod(), profile.estimatedCost()));
            }
        }
        return futures.isEmpty() ? List.of() : List.copyOf(futures);
    }

    private static CompletableFuture<WorldgenEffectProfile> scheduleByName(String className, String methodHint, int estimatedCost) {
        try {
            Class<?> unitClass = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return scheduleHotUnit(unitClass, methodHint, estimatedCost);
        } catch (ClassNotFoundException | LinkageError failure) {
            return CompletableFuture.completedFuture(WorldgenEffectProfileCache.global().profile(null, methodHint));
        }
    }
}
