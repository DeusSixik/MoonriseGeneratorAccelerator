package dev.sixik.generator_accelerator.common.worldgen.profile;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenEffectScannerTest {
    @AfterEach
    void reset() {
        WorldgenEffectProfileCache.global().clear();
        WorldgenProfileMetrics.reset();
        WorldgenProfileMetrics.setEnabled(false);
        GAScheduler.shutdownForTests();
    }

    @Test
    void classfileScannerFindsUnsafeConstantsAndMethods() {
        WorldgenEffectProfile profile = new WorldgenEffectScanner().scan(UnsafeFixture.class, "place");

        assertTrue(profile.readable());
        assertFalse(profile.fingerprint().isBlank());
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_IO));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_REFLECTION));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_THREADS));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_SYNCHRONIZED));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.USES_NATIVE));
        assertTrue(profile.hasHardUnsafeEffect());
    }

    @Test
    void cacheKeysByClassAndMethodHint() {
        WorldgenEffectProfileCache cache = new WorldgenEffectProfileCache(new WorldgenEffectScanner());

        WorldgenEffectProfile first = cache.profile(PureFixture.class, "compute");
        WorldgenEffectProfile second = cache.profile(PureFixture.class, "compute");
        WorldgenEffectProfile third = cache.profile(PureFixture.class, "place");

        assertEquals(first, second);
        assertEquals(2, cache.size());
        assertEquals(1L, cache.hits());
        assertEquals(2L, cache.misses());
        assertEquals(first.fingerprint(), third.fingerprint());
    }

    @Test
    void smallBudgetFailsClosed() {
        WorldgenEffectProfile profile = new WorldgenEffectScanner(1024, 16, 1).scan(UnsafeFixture.class, "place");

        assertFalse(profile.readable());
        assertTrue(profile.budgetExceeded() || profile.hasEffect(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD));
        assertTrue(profile.hasEffect(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD));
    }

    @Test
    void schedulerUsesCompileLaneForHotAnalysis() throws Exception {
        WorldgenEffectProfile profile = WorldgenEffectAnalysisScheduler
                .scheduleHotUnit(PureFixture.class, "compute", WorldgenEffectAnalysisScheduler.DEFAULT_HOT_COST_THRESHOLD)
                .get(5, TimeUnit.SECONDS);

        assertTrue(profile.readable());
        assertEquals(1, WorldgenEffectProfileCache.global().size());
    }

    @Test
    void metricsExposeEffectAnalysisCacheAndHardUnsafeCounts() {
        WorldgenProfileMetrics.setEnabled(true);
        WorldgenUnitProfile profile = new WorldgenUnitProfile(
                "unknown:unsafe",
                "examplemod",
                UnsafeFixture.class.getName(),
                "",
                "",
                0L,
                "place",
                1,
                java.util.EnumSet.of(WorldgenEffectFlag.USES_IO),
                WorldgenSafetyTier.SERIAL_ISOLATED,
                java.util.List.of(),
                "effect scan unsafe: io constant"
        );
        WorldgenProfileMetrics.record(profile);
        WorldgenEffectProfileCache.global().profile(PureFixture.class, "compute");
        WorldgenEffectProfileCache.global().profile(PureFixture.class, "compute");

        Map<String, Object> snapshot = WorldgenProfileMetrics.snapshot();
        Map<?, ?> effectAnalysis = (Map<?, ?>) snapshot.get("effectAnalysis");

        assertEquals(1L, effectAnalysis.get("hardUnsafeUnits"));
        assertEquals(1, effectAnalysis.get("cacheSize"));
        assertEquals(1L, effectAnalysis.get("cacheHits"));
        assertEquals(1L, effectAnalysis.get("cacheMisses"));
    }

    private static final class PureFixture {
        int compute(int input) {
            return input + 1;
        }
    }

    private static class UnsafeFixture {
        @SuppressWarnings("unused")
        private java.io.File file;
        @SuppressWarnings("unused")
        private java.lang.reflect.Method method;
        @SuppressWarnings("unused")
        private Thread thread;

        synchronized int guarded() {
            return 1;
        }

        native int nativeCall();
    }
}
