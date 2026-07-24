package dev.sixik.generator_accelerator.common.density.compiler.cache;

import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RandomStateCompileBudget;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional runtime parity check for generated {@link DfcCellFillAccess#dfc$fillCell}.
 */
public final class DfcCellFillParity {
    public static final boolean ENABLED = GAConfigHolder.getConfig().dfc.cellFillParity;

    private static final int MAX_CHECKS = Math.max(0, GAConfigHolder.getConfig().dfc.cellFillParityMaxChecks);
    private static final double EPSILON = GAConfigHolder.getConfig().dfc.cellFillParityEpsilon;
    private static volatile boolean ACTIVE = ENABLED && MAX_CHECKS > 0;

    private static final AtomicInteger REMAINING = new AtomicInteger(MAX_CHECKS);
    private static final LongAdder CHECKS = new LongAdder();
    private static final LongAdder PASSES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder SKIPPED = new LongAdder();
    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder FAST_ELIGIBLE = new LongAdder();
    private static final LongAdder LAZY_FAST_ELIGIBLE = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, Boolean> FALLBACK_CLASSES = new ConcurrentHashMap<>();

    private DfcCellFillParity() {
    }

    public record Stats(boolean enabled, int remaining, long checks, long passes, long failures, long skipped,
                        long candidates, long fastEligible, long lazyFastEligible, long fallbacks, List<String> fallbackClasses,
                        int maxChecks, double epsilon) {
    }

    public static Stats snapshotStats() {
        return new Stats(ENABLED, REMAINING.get(), CHECKS.sum(), PASSES.sum(), FAILURES.sum(), SKIPPED.sum(),
                CANDIDATES.sum(), FAST_ELIGIBLE.sum(), LAZY_FAST_ELIGIBLE.sum(), FALLBACKS.sum(), new ArrayList<>(FALLBACK_CLASSES.keySet()),
                MAX_CHECKS, EPSILON);
    }

    public static boolean isActive() {
        return ACTIVE && RandomStateCompileBudget.hasAdmittedCompiles();
    }

    public static void recordCandidate(DensityFunction filler, boolean fastEligible) {
        recordCandidate(filler, fastEligible, false);
    }

    public static void recordCandidate(DensityFunction filler, boolean fastEligible, boolean lazyCompiled) {
        CANDIDATES.increment();
        if (fastEligible) {
            FAST_ELIGIBLE.increment();
            if (lazyCompiled) {
                LAZY_FAST_ELIGIBLE.increment();
            }
        } else {
            FALLBACKS.increment();
            if (FALLBACK_CLASSES.size() < 8) {
                FALLBACK_CLASSES.putIfAbsent(filler.getClass().getName(), Boolean.TRUE);
            }
        }
    }

    public static void check(DensityFunction filler, double[] fastValues, NoiseChunk chunk) {
        if (!ACTIVE) {
            return;
        }
        if (!claimCheck()) {
            return;
        }

        CHECKS.increment();
        double[] expected = new double[fastValues.length];
        try {
            filler.fillArray(expected, chunk);
        } catch (Throwable t) {
            FAILURES.increment();
            warnOnce("DFC cell-fill parity check failed while running fallback fillArray for "
                    + filler.getClass().getName(), t);
            return;
        }

        int badIndex = -1;
        double maxDiff = 0.0D;
        for (int i = 0; i < fastValues.length; i++) {
            double a = fastValues[i];
            double b = expected[i];
            if (Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b)) {
                continue;
            }
            double diff = Math.abs(a - b);
            if (!(diff <= EPSILON)) {
                badIndex = i;
                maxDiff = diff;
                break;
            }
            if (diff > maxDiff) {
                maxDiff = diff;
            }
        }

        if (badIndex >= 0) {
            FAILURES.increment();
            final int idx = badIndex;
            final double fast = fastValues[idx];
            final double slow = expected[idx];
            final double diff = maxDiff;
            warnOnce("DFC cell-fill parity mismatch in " + filler.getClass().getName()
                    + " at index " + idx + ": fast=" + fast + ", fillArray=" + slow + ", diff=" + diff, null);
        } else {
            PASSES.increment();
        }
    }

    private static boolean claimCheck() {
        while (true) {
            int current = REMAINING.get();
            if (current <= 0) {
                ACTIVE = false;
                return false;
            }
            int next = current - 1;
            if (REMAINING.compareAndSet(current, next)) {
                if (next <= 0) {
                    ACTIVE = false;
                }
                return true;
            }
        }
    }

    private static void warnOnce(String message, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            if (t == null) {
                DensityFunctionCompiler.LOGGER.warn(message);
            } else {
                DensityFunctionCompiler.LOGGER.warn(message, t);
            }
        }
    }
}
