package dev.sixik.generator_accelerator.common.noise;

import java.util.concurrent.atomic.AtomicInteger;

/** Lifecycle-local guard for opportunistic fillSlice interpolator root compilation. */
public final class FillSliceLazyCompileBudget {
    private static final AtomicInteger CLAIMED = new AtomicInteger();

    private FillSliceLazyCompileBudget() {
    }

    public static boolean tryClaim(int maxCompiles) {
        int max = Math.max(0, maxCompiles);
        while (true) {
            int current = CLAIMED.get();
            if (current >= max) {
                NoiseChunkTimingStats.recordFillSliceLazyCompileBudgetSkip();
                return false;
            }
            if (CLAIMED.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public static void reset() {
        CLAIMED.set(0);
    }
}
