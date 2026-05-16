package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class WorldgenOptimizerMetrics {
    private static final int TOP_LIMIT = 32;
    private static final AtomicLong RECOGNIZED = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong DEOPTS = new AtomicLong();
    private static final AtomicLong GUARD_FAILURES = new AtomicLong();
    private static final AtomicLong PARITY_MATCHES = new AtomicLong();
    private static final AtomicLong PARITY_MISMATCHES = new AtomicLong();
    private static final AtomicLongArray PATTERNS = new AtomicLongArray(WorldgenOptimizationPattern.values().length);
    private static final AtomicLongArray FAST_PATHS = new AtomicLongArray(WorldgenFastPathKind.values().length);
    private static final ConcurrentHashMap<String, AtomicLong> NAMESPACES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> REASONS = new ConcurrentHashMap<>();

    private WorldgenOptimizerMetrics() {
    }

    public static void recordRecognized(WorldgenGeneratedPlan plan, String namespace) {
        if (plan == null) return;
        RECOGNIZED.incrementAndGet();
        PATTERNS.incrementAndGet(plan.pattern().ordinal());
        FAST_PATHS.incrementAndGet(plan.fastPathKind().ordinal());
        increment(NAMESPACES, namespace == null || namespace.isBlank() ? "unknown" : namespace);
    }

    public static void recordFallback(WorldgenDeoptReason reason, String message) {
        FALLBACKS.incrementAndGet();
        increment(REASONS, reason.name());
        if (message != null && !message.isBlank()) {
            increment(REASONS, message);
        }
    }

    public static void recordDeopt(WorldgenDeoptReason reason, String message) {
        DEOPTS.incrementAndGet();
        if (reason == WorldgenDeoptReason.GUARD_MISMATCH) {
            GUARD_FAILURES.incrementAndGet();
        }
        increment(REASONS, reason.name());
        if (message != null && !message.isBlank()) {
            increment(REASONS, message);
        }
    }

    public static void recordParityMatch() {
        PARITY_MATCHES.incrementAndGet();
    }

    public static void recordParityMismatch() {
        PARITY_MISMATCHES.incrementAndGet();
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "worldgen-optimizer-v1");
        out.put("recognized", RECOGNIZED.get());
        out.put("fallbacks", FALLBACKS.get());
        out.put("deopts", DEOPTS.get());
        out.put("guardFailures", GUARD_FAILURES.get());
        out.put("parityMatches", PARITY_MATCHES.get());
        out.put("parityMismatches", PARITY_MISMATCHES.get());
        out.put("patterns", enumCounts(WorldgenOptimizationPattern.values(), PATTERNS));
        out.put("fastPaths", enumCounts(WorldgenFastPathKind.values(), FAST_PATHS));
        out.put("namespaces", topCounts(NAMESPACES));
        out.put("reasons", topCounts(REASONS));
        return out;
    }

    public static void reset() {
        RECOGNIZED.set(0L);
        FALLBACKS.set(0L);
        DEOPTS.set(0L);
        GUARD_FAILURES.set(0L);
        PARITY_MATCHES.set(0L);
        PARITY_MISMATCHES.set(0L);
        for (WorldgenOptimizationPattern value : WorldgenOptimizationPattern.values()) {
            PATTERNS.set(value.ordinal(), 0L);
        }
        for (WorldgenFastPathKind value : WorldgenFastPathKind.values()) {
            FAST_PATHS.set(value.ordinal(), 0L);
        }
        NAMESPACES.clear();
        REASONS.clear();
    }

    private static void increment(ConcurrentHashMap<String, AtomicLong> counters, String key) {
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
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
                .sorted(Comparator.<Map.Entry<String, AtomicLong>>comparingLong(entry -> entry.getValue().get())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .forEach(entry -> out.put(entry.getKey(), entry.getValue().get()));
        return out;
    }
}
