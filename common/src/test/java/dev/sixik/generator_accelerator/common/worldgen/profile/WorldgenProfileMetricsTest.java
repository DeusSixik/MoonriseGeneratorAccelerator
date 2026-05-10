package dev.sixik.generator_accelerator.common.worldgen.profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldgenProfileMetricsTest {

    @AfterEach
    void reset() {
        WorldgenProfileMetrics.reset();
        WorldgenProfileMetrics.setEnabled(false);
    }

    @Test
    void disabledMetricsIgnoreProfiles() {
        WorldgenProfileMetrics.setEnabled(false);

        WorldgenProfileMetrics.record(profile("minecraft", WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES, "known vanilla feature constant"));

        Map<String, Object> snapshot = WorldgenProfileMetrics.snapshot();
        assertEquals(0L, snapshot.get("totalUnits"));
        assertEquals(0L, tierCount(snapshot, WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES));
    }

    @Test
    void recordsTierEffectsNamespacesAndReasons() {
        WorldgenProfileMetrics.setEnabled(true);

        WorldgenProfileMetrics.record(profile("minecraft", WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES, "known vanilla feature constant"));
        WorldgenProfileMetrics.record(profile("examplemod", WorldgenSafetyTier.SERIAL_ISOLATED, "unknown namespace defaults to serial safe vanilla"));
        WorldgenProfileMetrics.record(profile("examplemod", WorldgenSafetyTier.SERIAL_ISOLATED, "unknown namespace defaults to serial safe vanilla"));

        Map<String, Object> snapshot = WorldgenProfileMetrics.snapshot();
        assertEquals(3L, snapshot.get("totalUnits"));
        assertEquals(12L, snapshot.get("estimatedCostTotal"));
        assertEquals(1L, tierCount(snapshot, WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES));
        assertEquals(2L, tierCount(snapshot, WorldgenSafetyTier.SERIAL_ISOLATED));
        assertEquals(3L, effectCount(snapshot, WorldgenEffectFlag.WRITES_BLOCKS));
        assertEquals(2L, namedCount(snapshot, "namespaces", "examplemod"));
        assertEquals(2L, namedCount(snapshot, "fallbackReasons", "unknown namespace defaults to serial safe vanilla"));
    }

    @Test
    void resetClearsAggregatesButKeepsEnabledFlag() {
        WorldgenProfileMetrics.setEnabled(true);
        WorldgenProfileMetrics.record(profile("minecraft", WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE, "known placement or selector acceleration only"));

        WorldgenProfileMetrics.reset();

        Map<String, Object> snapshot = WorldgenProfileMetrics.snapshot();
        assertEquals(true, snapshot.get("enabled"));
        assertEquals(0L, snapshot.get("totalUnits"));
        assertEquals(0L, tierCount(snapshot, WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE));
    }

    private static WorldgenUnitProfile profile(String namespace, WorldgenSafetyTier tier, String reason) {
        return new WorldgenUnitProfile(
                "id",
                namespace,
                "example.Feature",
                "",
                "",
                0L,
                "Feature.place",
                4,
                EnumSet.of(WorldgenEffectFlag.WRITES_BLOCKS, WorldgenEffectFlag.USES_RANDOM),
                tier,
                List.of(),
                reason
        );
    }

    @SuppressWarnings("unchecked")
    private static long tierCount(Map<String, Object> snapshot, WorldgenSafetyTier tier) {
        return (long) ((Map<String, Object>) snapshot.get("tiers")).get(tier.name());
    }

    @SuppressWarnings("unchecked")
    private static long effectCount(Map<String, Object> snapshot, WorldgenEffectFlag flag) {
        return (long) ((Map<String, Object>) snapshot.get("effects")).get(flag.name());
    }

    @SuppressWarnings("unchecked")
    private static long namedCount(Map<String, Object> snapshot, String group, String key) {
        return (long) ((Map<String, Object>) snapshot.get(group)).get(key);
    }
}
