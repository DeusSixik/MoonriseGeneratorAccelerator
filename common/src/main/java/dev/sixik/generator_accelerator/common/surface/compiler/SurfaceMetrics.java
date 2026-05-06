package dev.sixik.generator_accelerator.common.surface.compiler;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;

public final class SurfaceMetrics {
    private static final boolean ENABLED = Boolean.getBoolean("ga.surface.metrics");
    private static final LongAdder COMPILED_PROGRAMS = new LongAdder();
    private static final LongAdder FALLBACK_ISLANDS = new LongAdder();
    private static final LongAdder CONDITION_CACHE_HITS = new LongAdder();
    private static final LongAdder CONDITION_CACHE_MISSES = new LongAdder();
    private static final LongAdder ACTIVE_MASK_EARLY_EXITS = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> FALLBACK_RULE_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FALLBACK_CONDITION_CLASSES = new ConcurrentHashMap<>();

    private SurfaceMetrics() {
    }

    static void compiledProgram() {
        if (!ENABLED) return;
        COMPILED_PROGRAMS.increment();
    }

    static void fallbackIsland() {
        if (!ENABLED) return;
        FALLBACK_ISLANDS.increment();
    }

    static void fallbackRule(Class<?> ruleClass) {
        if (!ENABLED) return;
        FALLBACK_RULE_CLASSES.computeIfAbsent(ruleClass.getName(), ignored -> new LongAdder()).increment();
    }

    static void fallbackCondition(Class<?> conditionClass) {
        if (!ENABLED) return;
        FALLBACK_CONDITION_CLASSES.computeIfAbsent(conditionClass.getName(), ignored -> new LongAdder()).increment();
    }

    static void conditionCacheHit() {
        if (!ENABLED) return;
        CONDITION_CACHE_HITS.increment();
    }

    static void conditionCacheMiss() {
        if (!ENABLED) return;
        CONDITION_CACHE_MISSES.increment();
    }

    static void activeMaskEarlyExit() {
        if (!ENABLED) return;
        ACTIVE_MASK_EARLY_EXITS.increment();
    }

    public static long compiledPrograms() {
        return COMPILED_PROGRAMS.sum();
    }

    public static long fallbackIslands() {
        return FALLBACK_ISLANDS.sum();
    }

    public static long fallbackRuleClassCount(String className) {
        LongAdder counter = FALLBACK_RULE_CLASSES.get(className);
        return counter == null ? 0L : counter.sum();
    }

    public static long fallbackConditionClassCount(String className) {
        LongAdder counter = FALLBACK_CONDITION_CLASSES.get(className);
        return counter == null ? 0L : counter.sum();
    }
}
