package dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline;

import com.google.common.collect.MapMaker;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-warms the JIT cache after the registries have been bound.
 *
 * <p>This runs once on {@code ServerStartingEvent} (and again when datapacks are
 * synced for all players after a reload, because reload rebuilds the registries) — by which
 * point every {@link net.minecraft.core.Holder.Reference Holder.Reference} into
 * {@link Registries#DENSITY_FUNCTION} is resolvable. That makes it the right place to
 * (a) construct a {@link RandomState} for every {@link NoiseGeneratorSettings} so the
 * {@link dev.sixik.generator_accelerator.common.density.compiler.mixin.RandomStateMixin RandomStateMixin}
 * runs the same wired {@link NoiseRouter} compile as real worldgen, and (b) compile every
 * standalone {@code DensityFunction} so any future router that points at it gets a hot
 * cache lookup instead of a cold ASM emit.
 *
 * <p>We cannot use {@link NoiseGeneratorSettings#noiseRouter()} alone: that returns the
 * static data-pack template, which is never replaced in place. Compilation only runs at
 * the end of {@link RandomState}'s constructor after {@code mapAll(NoiseWiringHelper)}.
 *
 * <p>Without this step, the first chunk to spawn would pay the entire compile cost on
 * the chunk-gen worker thread — that's a multi-hundred-millisecond stall per noise
 * preset, which is exactly the latency we're trying to remove.
 */
public final class RegistryWarmer {

    private static final String MAX_SETTINGS_PROPERTY = "dfc.warmer.maxSettings";
    private static final String MAX_DENSITY_FUNCTIONS_PROPERTY = "dfc.warmer.maxDensityFunctions";

    private static final Set<RegistryGenerationKey> WARMED_GENERATIONS = ConcurrentHashMap.newKeySet();
    private static final Set<NoiseGeneratorSettings> WARMED_NOISE_SETTINGS = Collections.newSetFromMap(
            new MapMaker().weakKeys().concurrencyLevel(4).<NoiseGeneratorSettings, Boolean>makeMap());
    private static final Set<DensityFunction> WARMED_DENSITY_FUNCTIONS = Collections.newSetFromMap(
            new MapMaker().weakKeys().concurrencyLevel(4).<DensityFunction, Boolean>makeMap());

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong SKIPPED_DUPLICATE_CALLS = new AtomicLong();
    private static final AtomicLong SKIPPED_DUPLICATE_ENTRIES = new AtomicLong();
    private static final AtomicLong WARMED_ROUTERS = new AtomicLong();
    private static final AtomicLong WARMED_DENSITY_FUNCTIONS_COUNT = new AtomicLong();
    private static final AtomicLong FAILED_ENTRIES = new AtomicLong();
    private static final AtomicLong BUDGET_SKIPS = new AtomicLong();

    private RegistryWarmer() {}

    public static void warmAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CALLS.incrementAndGet();
        RegistryGenerationKey generation = generationKey(server);
        if (generation != null && !WARMED_GENERATIONS.add(generation)) {
            SKIPPED_DUPLICATE_CALLS.incrementAndGet();
            DensityFunctionCompiler.LOGGER.info(
                    "DFC: registry warm-up skipped; this server registry generation was already warmed");
            return;
        }

        // Trigger the same RandomState + wired router compile as production (mixin@RETURN).
        boolean noiseOk = warmNoiseSettings(server);
        // Then compile any density functions that aren't reachable from a router
        // (mod-added DFs registered for use elsewhere). Idempotent w.r.t. the
        // identity-keyed cache that the router walk already populated.
        boolean densityOk = warmDensityFunctions(server);
        if (generation != null && (!noiseOk || !densityOk)) {
            WARMED_GENERATIONS.remove(generation);
        }
    }

    private static boolean warmNoiseSettings(MinecraftServer server) {
        try {
            Registry<NoiseGeneratorSettings> registry = server.registryAccess()
                    .registryOrThrow(Registries.NOISE_SETTINGS);
            HolderGetter<NormalNoise.NoiseParameters> noiseGetter =
                    server.registryAccess().lookupOrThrow(Registries.NOISE);
            long levelSeed = server.overworld().getSeed();
            int maxSettings = budgetLimit(MAX_SETTINGS_PROPERTY);

            int total = 0;
            int compiled = 0;
            int duplicates = 0;
            int budgetSkipped = 0;
            int failed = 0;
            int attempted = 0;
            for (NoiseGeneratorSettings settings : registry) {
                total++;
                if (!WARMED_NOISE_SETTINGS.add(settings)) {
                    duplicates++;
                    SKIPPED_DUPLICATE_ENTRIES.incrementAndGet();
                    continue;
                }
                if (attempted >= maxSettings) {
                    WARMED_NOISE_SETTINGS.remove(settings);
                    budgetSkipped++;
                    BUDGET_SKIPS.incrementAndGet();
                    continue;
                }
                attempted++;
                try {
                    // Matches production: wiring + RandomStateMixin compile at <init> RETURN.
                    RandomState state = RandomState.create(settings, noiseGetter, levelSeed);
                    if (routerHasDfcFields(state.router())) {
                        compiled++;
                        WARMED_ROUTERS.incrementAndGet();
                    }
                } catch (Throwable settingsErr) {
                    WARMED_NOISE_SETTINGS.remove(settings);
                    failed++;
                    FAILED_ENTRIES.incrementAndGet();
                    DensityFunctionCompiler.LOGGER.debug(
                            "DFC: warm-up couldn't build RandomState for a noise_settings entry; skipping",
                            settingsErr);
                }
            }
            DensityFunctionCompiler.LOGGER.info(
                    "DFC: warmed {}/{} noise_settings (RandomState + wired router compile); "
                            + "{} duplicate, {} budget, {} failed",
                    compiled, total, duplicates, budgetSkipped, failed);
            return true;
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC: noise_settings warm-up failed; lazy compilation will still pick up callers.", t);
            return false;
        }
    }

    private static boolean warmDensityFunctions(MinecraftServer server) {
        try {
            Registry<DensityFunction> registry = server.registryAccess()
                    .registryOrThrow(Registries.DENSITY_FUNCTION);
            CompilingVisitor visitor = CompilingVisitor.global();
            int maxDensityFunctions = budgetLimit(MAX_DENSITY_FUNCTIONS_PROPERTY);
            int total = 0;
            int warmed = 0;
            int duplicates = 0;
            int budgetSkipped = 0;
            int failed = 0;
            int attempted = 0;
            for (DensityFunction df : registry) {
                total++;
                if (!WARMED_DENSITY_FUNCTIONS.add(df)) {
                    duplicates++;
                    SKIPPED_DUPLICATE_ENTRIES.incrementAndGet();
                    continue;
                }
                if (attempted >= maxDensityFunctions) {
                    WARMED_DENSITY_FUNCTIONS.remove(df);
                    budgetSkipped++;
                    BUDGET_SKIPS.incrementAndGet();
                    continue;
                }
                attempted++;
                try {
                    visitor.apply(df);
                    warmed++;
                    WARMED_DENSITY_FUNCTIONS_COUNT.incrementAndGet();
                } catch (Throwable entryErr) {
                    WARMED_DENSITY_FUNCTIONS.remove(df);
                    failed++;
                    FAILED_ENTRIES.incrementAndGet();
                    DensityFunctionCompiler.LOGGER.debug(
                            "DFC: warm-up couldn't compile a density_function entry; skipping",
                            entryErr);
                }
            }
            DensityFunctionCompiler.LOGGER.info(
                    "DFC: warmed {}/{} density_function entries; {} duplicate, {} budget, "
                            + "{} failed; visitor cache now ~{} entries",
                    warmed, total, duplicates, budgetSkipped, failed, visitor.cacheSize());
            return true;
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC: density_function warm-up failed; lazy compilation will still pick up callers.", t);
            return false;
        }
    }

    private static RegistryGenerationKey generationKey(MinecraftServer server) {
        try {
            var registryAccess = server.registryAccess();
            Registry<NoiseGeneratorSettings> noiseSettings =
                    registryAccess.registryOrThrow(Registries.NOISE_SETTINGS);
            Registry<DensityFunction> densityFunctions =
                    registryAccess.registryOrThrow(Registries.DENSITY_FUNCTION);
            return new RegistryGenerationKey(server, registryAccess, noiseSettings, densityFunctions);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.debug(
                    "DFC: couldn't fingerprint registry generation for warm-up dedupe", t);
            return null;
        }
    }

    private static int budgetLimit(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0L) {
                return Integer.MAX_VALUE;
            }
            return parsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parsed;
        } catch (NumberFormatException e) {
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC: ignoring invalid warmer budget {}={} (expected non-negative integer or unset)",
                    property, raw);
            return Integer.MAX_VALUE;
        }
    }

    public static Stats snapshotStats() {
        return new Stats(
                CALLS.get(),
                SKIPPED_DUPLICATE_CALLS.get(),
                SKIPPED_DUPLICATE_ENTRIES.get(),
                WARMED_ROUTERS.get(),
                WARMED_DENSITY_FUNCTIONS_COUNT.get(),
                FAILED_ENTRIES.get(),
                BUDGET_SKIPS.get());
    }

    public record Stats(long calls, long skippedDuplicateCalls, long skippedDuplicateEntries,
                        long warmedRouters, long warmedDensityFunctions,
                        long failedEntries, long budgetSkips) {}

    private static final class RegistryGenerationKey {
        private final MinecraftServer server;
        private final Object registryAccess;
        private final Registry<NoiseGeneratorSettings> noiseSettings;
        private final Registry<DensityFunction> densityFunctions;
        private final int hash;

        RegistryGenerationKey(MinecraftServer server, Object registryAccess,
                Registry<NoiseGeneratorSettings> noiseSettings,
                Registry<DensityFunction> densityFunctions) {
            this.server = server;
            this.registryAccess = registryAccess;
            this.noiseSettings = noiseSettings;
            this.densityFunctions = densityFunctions;
            int h = System.identityHashCode(server);
            h = 31 * h + System.identityHashCode(registryAccess);
            h = 31 * h + System.identityHashCode(noiseSettings);
            h = 31 * h + System.identityHashCode(densityFunctions);
            this.hash = h;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RegistryGenerationKey other)) return false;
            return server == other.server
                    && registryAccess == other.registryAccess
                    && noiseSettings == other.noiseSettings
                    && densityFunctions == other.densityFunctions;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Heuristic: DFC is active on this router if any sampled top-level field is
     * {@link CompiledDensityFunction} (eager compile) or
     * {@link OnDemandCompilingDensityFunction} (lazy wrap).
     */
    private static boolean routerHasDfcFields(NoiseRouter router) {
        return fieldIsDfc(router.finalDensity())
                || fieldIsDfc(router.initialDensityWithoutJaggedness())
                || fieldIsDfc(router.temperature())
                || fieldIsDfc(router.vegetation())
                || fieldIsDfc(router.continents())
                || fieldIsDfc(router.depth())
                || fieldIsDfc(router.barrierNoise());
    }

    private static boolean fieldIsDfc(DensityFunction f) {
        return f instanceof CompiledDensityFunction
                || f instanceof OnDemandCompilingDensityFunction;
    }
}
