package dev.sixik.generator_accelerator.common.worldgen.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenRegistryScannerTest {
    private final WorldgenRegistryScanner scanner = new WorldgenRegistryScanner();

    @AfterEach
    void resetMetrics() {
        WorldgenProfileMetrics.reset();
        WorldgenProfileMetrics.setEnabled(false);
        WorldgenEffectProfileCache.global().clear();
    }

    @Test
    void scansMultipleRegistryKindsFromMaps() {
        Map<String, Object> densities = new LinkedHashMap<>();
        densities.put("minecraft:overworld/base", new Object());
        densities.put("examplemod:noise", new CustomWorldgenUnit());
        Map<String, Object> carvers = new LinkedHashMap<>();
        carvers.put("minecraft:cave", new Object());

        WorldgenRegistryScan scan = scanner.scan(
                7L,
                WorldgenRegistryScanner.RegistrySource.map(WorldgenUnitKind.DENSITY_FUNCTION, "density_function", densities),
                WorldgenRegistryScanner.RegistrySource.map(WorldgenUnitKind.CARVER, "carver", carvers)
        );

        assertEquals(7L, scan.epoch());
        assertEquals(3L, scan.totalUnits());
        assertEquals(2L, scan.count(WorldgenUnitKind.DENSITY_FUNCTION));
        assertEquals(1L, scan.count(WorldgenUnitKind.CARVER));
        assertEquals(3L, scan.cacheMisses());
        assertEquals(0L, scan.cacheHits());
    }

    @Test
    void sameEpochUsesCacheAndNewEpochReplacesProfileEpoch() {
        Map<String, Object> units = new LinkedHashMap<>();
        Object unit = new Object();
        units.put("minecraft:continentalness", unit);

        WorldgenRegistryScan first = scanner.scanMap(1L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);
        WorldgenRegistryScan cached = scanner.scanMap(1L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);
        WorldgenRegistryScan nextEpoch = scanner.scanMap(2L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);

        assertEquals(1L, first.cacheMisses());
        assertEquals(1L, cached.cacheHits());
        assertEquals(0L, cached.cacheMisses());
        assertEquals(2L, nextEpoch.profiles().get(0).registryEpoch());
        assertEquals(1L, nextEpoch.cacheMisses());
        assertEquals(0L, nextEpoch.cacheHits());
        assertEquals(1, scanner.cachedProfiles());
    }

    @Test
    void countsByKindTierNamespaceAndFallbackAreImmutable() {
        Map<String, Object> units = new LinkedHashMap<>();
        units.put("minecraft:base", new Object());
        units.put("examplemod:custom", new CustomWorldgenUnit());
        units.put("mysterymod:custom", new Object());

        WorldgenRegistryScan scan = scanner.scanMap(3L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);

        assertEquals(3L, scan.count(WorldgenUnitKind.DENSITY_FUNCTION));
        assertEquals(1L, scan.count(WorldgenSafetyTier.PURE_READ_ONLY));
        assertEquals(2L, scan.count(WorldgenSafetyTier.SERIAL_ISOLATED));
        assertEquals(1L, scan.countNamespace("minecraft"));
        assertEquals(1L, scan.countNamespace("examplemod"));
        assertEquals(2L, scan.countFallback("unknown namespace defaults to serial safe vanilla"));
        assertThrows(UnsupportedOperationException.class, () -> scan.countsByNamespace().put("new", 1L));
        assertThrows(UnsupportedOperationException.class, () -> scan.profiles().add(scan.profiles().get(0)));
    }

    @Test
    void unknownNamespaceStaysSerialClosed() {
        WorldgenRegistryScan scan = scanner.scanIterable(
                4L,
                WorldgenUnitKind.STRUCTURE,
                "structure",
                List.of(Map.entry("unknownmod:tower", new CustomWorldgenUnit()))
        );

        WorldgenUnitProfile profile = scan.profiles().get(0);
        assertEquals("unknownmod", profile.namespace());
        assertEquals(WorldgenSafetyTier.SERIAL_ISOLATED, profile.safetyTier());
        assertTrue(profile.hasEffect(WorldgenEffectFlag.CALLS_UNKNOWN_METHOD));
        assertTrue(profile.fallbackReason().contains("unknown namespace defaults"));
    }

    @Test
    void clearAndResetDropCacheAndEpoch() {
        Map<String, Object> units = Map.of("minecraft:base", new Object());
        scanner.resetEpoch(42L);
        scanner.scanMap(42L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);

        assertEquals(1, scanner.cachedProfiles());

        scanner.clear();
        assertEquals(0, scanner.cachedProfiles());
        assertEquals(42L, scanner.currentEpoch());

        scanner.scanMap(42L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);
        assertEquals(1, scanner.cachedProfiles());

        scanner.reset();
        assertEquals(0, scanner.cachedProfiles());
        assertEquals(0L, scanner.currentEpoch());
    }

    @Test
    void metricsRecordCachedProfilesOncePerScanWhenEnabled() {
        WorldgenProfileMetrics.setEnabled(true);
        Map<String, Object> units = Map.of("minecraft:base", new Object());

        scanner.scanMap(5L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);
        scanner.scanMap(5L, WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units);

        Map<String, Object> metrics = WorldgenProfileMetrics.snapshot();
        assertEquals(2L, metrics.get("totalUnits"));
    }

    @Test
    void orchestratorPublishesReloadAndRuntimeScans() {
        WorldgenProfileMetrics.setEnabled(true);
        WorldgenRegistryScanOrchestrator orchestrator = new WorldgenRegistryScanOrchestrator(new WorldgenRegistryScanner());
        Map<String, Object> units = Map.of("minecraft:base", new Object());
        long[] listenerEpoch = new long[] { -1L };
        orchestrator.addListener((scan, reload) -> listenerEpoch[0] = reload ? scan.epoch() : -scan.epoch());

        WorldgenRegistryScan reload = orchestrator.reloadScan(
                WorldgenRegistryScanner.RegistrySource.map(WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units)
        );
        WorldgenRegistryScan runtime = orchestrator.runtimeScan(
                WorldgenRegistryScanner.RegistrySource.map(WorldgenUnitKind.DENSITY_FUNCTION, "density_function", units)
        );

        assertEquals(1L, reload.epoch());
        assertEquals(1L, runtime.epoch());
        assertEquals(runtime, orchestrator.currentScan());
        assertEquals(-1L, listenerEpoch[0]);
        assertEquals(1L, ((Map<?, ?>) orchestrator.snapshot()).get("epoch"));
        Map<String, Object> metrics = WorldgenProfileMetrics.snapshot();
        assertEquals(2L, namedCount(metrics, "registryScans", "scans"));
        assertEquals(1L, namedCount(metrics, "registryScans", "reloadScans"));
        assertEquals(2L, namedCount(metrics, "registryScans", "units"));
        assertEquals(1L, namedCount(metrics, "registryScans", "cacheHits"));
        assertEquals(1L, namedCount(metrics, "registryScans", "cacheMisses"));
    }

    @Test
    void orchestratorListenerFailuresAreMeasuredWhenEnabled() {
        WorldgenProfileMetrics.setEnabled(true);
        WorldgenRegistryScanOrchestrator orchestrator = new WorldgenRegistryScanOrchestrator(new WorldgenRegistryScanner());
        orchestrator.addListener((scan, reload) -> {
            throw new IllegalStateException("boom");
        });

        orchestrator.reloadScan(WorldgenRegistryScanner.RegistrySource.map(
                WorldgenUnitKind.DENSITY_FUNCTION,
                "density_function",
                Map.of("minecraft:base", new Object())
        ));

        assertEquals(1L, namedCount(WorldgenProfileMetrics.snapshot(), "registryScans", "listenerFailures"));
    }

    private static final class CustomWorldgenUnit {
    }

    @SuppressWarnings("unchecked")
    private static long namedCount(Map<String, Object> snapshot, String group, String key) {
        return (long) ((Map<String, Object>) snapshot.get(group)).get(key);
    }
}
