package dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle-local guard for synchronous RandomState DFC compilation.
 *
 * <p>The old behaviour compiled every constructed RandomState eagerly. That is a bad
 * failure mode in modpacks that create many transient RandomStates during world load:
 * exact fingerprints include runtime sampler identities, so thousands of roots can turn
 * into thousands of codegen misses before the player ever enters the world.</p>
 */
public final class RandomStateCompileBudget {
    public static final String MAX_PROPERTY = "ga.dfc.randomStateCompile.max";
    public static final String COMPILE_SAMPLER_PROPERTY = "ga.dfc.randomStateCompile.sampler";
    public static final String ROUTER_ROOTS_PROPERTY = "ga.dfc.randomStateCompile.routerRoots";

    private static final int DEFAULT_MAX = 0;

    private static final AtomicInteger ACQUIRED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static final AtomicBoolean LOGGED_DISABLED = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_EXHAUSTED = new AtomicBoolean();

    private RandomStateCompileBudget() {
    }

    public static boolean tryAcquire(NoiseGeneratorSettings settings, long levelSeed) {
        int max = Integer.getInteger(MAX_PROPERTY, DEFAULT_MAX);
        if (max < 0) {
            ACQUIRED.incrementAndGet();
            return true;
        }
        if (max == 0) {
            SKIPPED.incrementAndGet();
            if (LOGGED_DISABLED.compareAndSet(false, true)) {
                DensityFunctionCompiler.LOGGER.info(
                        "DFC RandomState eager compilation is disabled ({}=0); routers stay vanilla",
                        MAX_PROPERTY);
            }
            return false;
        }

        while (true) {
            int current = ACQUIRED.get();
            if (current >= max) {
                SKIPPED.incrementAndGet();
                if (LOGGED_EXHAUSTED.compareAndSet(false, true)) {
                    DensityFunctionCompiler.LOGGER.info(
                            "DFC RandomState eager compile budget exhausted (max={} this lifecycle); "
                                    + "leaving further RandomStates vanilla. Set dfc.randomStateCompileMax=-1 "
                                    + "in the GA config for old unlimited mode.",
                            max);
                }
                return false;
            }
            if (ACQUIRED.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public static boolean shouldCompileSampler() {
        return Boolean.parseBoolean(System.getProperty(COMPILE_SAMPLER_PROPERTY, "true"));
    }

    public static String routerRoots() {
        String value = System.getProperty(ROUTER_ROOTS_PROPERTY, "all");
        return value == null || value.isBlank() ? "all" : value.trim();
    }

    public static boolean hasAdmittedCompiles() {
        return ACQUIRED.get() > 0;
    }

    public static Stats snapshotStats() {
        return new Stats(ACQUIRED.get(), SKIPPED.get(), Integer.getInteger(MAX_PROPERTY, DEFAULT_MAX),
                shouldCompileSampler(), routerRoots());
    }

    public static void reset() {
        ACQUIRED.set(0);
        SKIPPED.set(0);
        LOGGED_DISABLED.set(false);
        LOGGED_EXHAUSTED.set(false);
    }

    public record Stats(int acquired, int skipped, int max, boolean compileSampler, String routerRoots) {
    }
}
