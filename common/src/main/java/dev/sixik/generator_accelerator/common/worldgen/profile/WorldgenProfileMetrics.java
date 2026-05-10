package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Aggregates cheap classifier results for diagnostics and staged rollout. */
public final class WorldgenProfileMetrics {
    public static volatile boolean ENABLED = Boolean.getBoolean("ga.worldgenProfile.metrics");

    private static final int TOP_LIMIT = 32;
    private static final AtomicLong TOTAL_UNITS = new AtomicLong();
    private static final AtomicLong ESTIMATED_COST_TOTAL = new AtomicLong();
    private static final AtomicLongArray TIER_COUNTS = new AtomicLongArray(WorldgenSafetyTier.values().length);
    private static final AtomicLongArray EFFECT_COUNTS = new AtomicLongArray(WorldgenEffectFlag.values().length);
    private static final ConcurrentHashMap<String, AtomicLong> NAMESPACE_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> FALLBACK_REASON_COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> CLASS_COUNTS = new ConcurrentHashMap<>();

    private WorldgenProfileMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static void record(WorldgenUnitProfile profile) {
        if (!ENABLED || profile == null) {
            return;
        }

        TOTAL_UNITS.incrementAndGet();
        ESTIMATED_COST_TOTAL.addAndGet(Math.max(0, profile.estimatedCost()));

        WorldgenSafetyTier tier = profile.safetyTier();
        TIER_COUNTS.incrementAndGet(tier.ordinal());

        Set<WorldgenEffectFlag> flags = profile.effectFlags();
        for (WorldgenEffectFlag flag : flags) {
            EFFECT_COUNTS.incrementAndGet(flag.ordinal());
        }

        increment(NAMESPACE_COUNTS, safeKey(profile.namespace(), "unknown"));
        increment(CLASS_COUNTS, safeKey(profile.className(), "unknown"));
        if (!profile.fallbackReason().isBlank()) {
            increment(FALLBACK_REASON_COUNTS, profile.fallbackReason());
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("totalUnits", TOTAL_UNITS.get());
        out.put("estimatedCostTotal", ESTIMATED_COST_TOTAL.get());
        out.put("tiers", enumCounts(WorldgenSafetyTier.values(), TIER_COUNTS));
        out.put("effects", enumCounts(WorldgenEffectFlag.values(), EFFECT_COUNTS));
        out.put("namespaces", topCounts(NAMESPACE_COUNTS));
        out.put("fallbackReasons", topCounts(FALLBACK_REASON_COUNTS));
        out.put("classes", topCounts(CLASS_COUNTS));
        return out;
    }

    public static void reset() {
        TOTAL_UNITS.set(0L);
        ESTIMATED_COST_TOTAL.set(0L);
        for (int i = 0; i < WorldgenSafetyTier.values().length; i++) {
            TIER_COUNTS.set(i, 0L);
        }
        for (int i = 0; i < WorldgenEffectFlag.values().length; i++) {
            EFFECT_COUNTS.set(i, 0L);
        }
        NAMESPACE_COUNTS.clear();
        FALLBACK_REASON_COUNTS.clear();
        CLASS_COUNTS.clear();
    }

    private static void increment(ConcurrentHashMap<String, AtomicLong> counters, String key) {
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static String safeKey(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <E extends Enum<E>> Map<String, Object> enumCounts(E[] values, AtomicLongArray counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (E value : values) {
            out.put(value.name(), counts.get(value.ordinal()));
        }
        return out;
    }

    private static Map<String, Object> topCounts(ConcurrentHashMap<String, AtomicLong> counters) {
        Map<String, Object> out = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, AtomicLong>>comparingLong(entry -> entry.getValue().get())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .forEach(entry -> out.put(entry.getKey(), entry.getValue().get()));
        return out;
    }
}
