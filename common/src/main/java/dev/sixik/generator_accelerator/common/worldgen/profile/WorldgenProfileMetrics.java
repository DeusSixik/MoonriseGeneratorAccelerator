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
    private static final AtomicLong REGISTRY_SCANS = new AtomicLong();
    private static final AtomicLong REGISTRY_RELOAD_SCANS = new AtomicLong();
    private static final AtomicLong REGISTRY_SCAN_UNITS = new AtomicLong();
    private static final AtomicLong REGISTRY_SCAN_CACHE_HITS = new AtomicLong();
    private static final AtomicLong REGISTRY_SCAN_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong REGISTRY_SCAN_LISTENER_FAILURES = new AtomicLong();
    private static final AtomicLong LAST_REGISTRY_SCAN_EPOCH = new AtomicLong();
    private static final AtomicLong HARD_UNSAFE_UNITS = new AtomicLong();
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
        if (hasHardUnsafeEffect(flags)) {
            HARD_UNSAFE_UNITS.incrementAndGet();
        }

        increment(NAMESPACE_COUNTS, safeKey(profile.namespace(), "unknown"));
        increment(CLASS_COUNTS, safeKey(profile.className(), "unknown"));
        if (!profile.fallbackReason().isBlank()) {
            increment(FALLBACK_REASON_COUNTS, profile.fallbackReason());
        }
    }

    public static void recordRegistryScan(WorldgenRegistryScan scan, boolean reload) {
        if (!ENABLED || scan == null) {
            return;
        }
        REGISTRY_SCANS.incrementAndGet();
        if (reload) {
            REGISTRY_RELOAD_SCANS.incrementAndGet();
        }
        REGISTRY_SCAN_UNITS.addAndGet(scan.totalUnits());
        REGISTRY_SCAN_CACHE_HITS.addAndGet(scan.cacheHits());
        REGISTRY_SCAN_CACHE_MISSES.addAndGet(scan.cacheMisses());
        LAST_REGISTRY_SCAN_EPOCH.set(scan.epoch());
    }

    public static void recordRegistryScanListenerFailure() {
        if (ENABLED) {
            REGISTRY_SCAN_LISTENER_FAILURES.incrementAndGet();
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("totalUnits", TOTAL_UNITS.get());
        out.put("estimatedCostTotal", ESTIMATED_COST_TOTAL.get());
        out.put("tiers", enumCounts(WorldgenSafetyTier.values(), TIER_COUNTS));
        out.put("effects", enumCounts(WorldgenEffectFlag.values(), EFFECT_COUNTS));
        out.put("registryScans", registryScanCounts());
        out.put("effectAnalysis", effectAnalysisCounts());
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
        REGISTRY_SCANS.set(0L);
        REGISTRY_RELOAD_SCANS.set(0L);
        REGISTRY_SCAN_UNITS.set(0L);
        REGISTRY_SCAN_CACHE_HITS.set(0L);
        REGISTRY_SCAN_CACHE_MISSES.set(0L);
        REGISTRY_SCAN_LISTENER_FAILURES.set(0L);
        LAST_REGISTRY_SCAN_EPOCH.set(0L);
        HARD_UNSAFE_UNITS.set(0L);
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

    private static Map<String, Object> registryScanCounts() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scans", REGISTRY_SCANS.get());
        out.put("reloadScans", REGISTRY_RELOAD_SCANS.get());
        out.put("units", REGISTRY_SCAN_UNITS.get());
        out.put("cacheHits", REGISTRY_SCAN_CACHE_HITS.get());
        out.put("cacheMisses", REGISTRY_SCAN_CACHE_MISSES.get());
        out.put("listenerFailures", REGISTRY_SCAN_LISTENER_FAILURES.get());
        out.put("lastEpoch", LAST_REGISTRY_SCAN_EPOCH.get());
        return out;
    }

    private static Map<String, Object> effectAnalysisCounts() {
        Map<String, Object> out = new LinkedHashMap<>();
        WorldgenEffectProfileCache cache = WorldgenEffectProfileCache.global();
        out.put("cacheSize", cache.size());
        out.put("cacheHits", cache.hits());
        out.put("cacheMisses", cache.misses());
        out.put("hardUnsafeUnits", HARD_UNSAFE_UNITS.get());
        return out;
    }

    private static boolean hasHardUnsafeEffect(Set<WorldgenEffectFlag> flags) {
        return flags.contains(WorldgenEffectFlag.USES_REFLECTION)
                || flags.contains(WorldgenEffectFlag.USES_NATIVE)
                || flags.contains(WorldgenEffectFlag.USES_IO)
                || flags.contains(WorldgenEffectFlag.USES_THREADS)
                || flags.contains(WorldgenEffectFlag.USES_SYNCHRONIZED)
                || flags.contains(WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE)
                || flags.contains(WorldgenEffectFlag.CROSS_CHUNK_WRITE);
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
