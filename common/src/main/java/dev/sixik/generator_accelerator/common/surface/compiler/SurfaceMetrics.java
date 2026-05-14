package dev.sixik.generator_accelerator.common.surface.compiler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.Set;

public final class SurfaceMetrics {
    public static volatile boolean ENABLED = Boolean.getBoolean("ga.surface.metrics");

    private static final LongAdder COMPILED_PROGRAMS = new LongAdder();
    private static final LongAdder IR_PROGRAMS = new LongAdder();
    private static final LongAdder IR_FALLBACKS = new LongAdder();
    private static final LongAdder IR_FALLBACK_RULE_NODES = new LongAdder();
    private static final LongAdder IR_FALLBACK_CONDITION_NODES = new LongAdder();
    private static final LongAdder INTERPRETED_PROGRAMS = new LongAdder();
    private static final LongAdder OPTIMIZED_PROGRAMS = new LongAdder();

    private static final LongAdder CACHE_HITS = new LongAdder();
    private static final LongAdder CACHE_MISSES = new LongAdder();
    private static final LongAdder LAST_ENTRY_HITS = new LongAdder();
    private static final LongAdder UNSUPPORTED_PROGRAMS = new LongAdder();
    private static final LongAdder UNSUPPORTED_CACHE_HITS = new LongAdder();
    private static final LongAdder VANILLA_FALLBACKS = new LongAdder();

    private static final LongAdder SECTIONS_PROCESSED = new LongAdder();
    private static final LongAdder EMPTY_SECTIONS_SKIPPED = new LongAdder();
    private static final LongAdder RAW_BLOCK_ARRAY_MISSES = new LongAdder();
    private static final LongAdder STONELESS_SECTIONS_SKIPPED = new LongAdder();

    private static final LongAdder FALLBACK_ISLANDS = new LongAdder();
    private static final LongAdder CONDITION_CACHE_HITS = new LongAdder();
    private static final LongAdder CONDITION_CACHE_MISSES = new LongAdder();
    private static final LongAdder ACTIVE_MASK_EARLY_EXITS = new LongAdder();

    private static final TimerCounter CACHE_LOOKUP_TIME = new TimerCounter();
    private static final TimerCounter COMPILE_TIME = new TimerCounter();
    private static final TimerCounter BIOME_PREP_TIME = new TimerCounter();
    private static final TimerCounter SURFACE_DEPTH_TIME = new TimerCounter();
    private static final TimerCounter SECONDARY_SURFACE_TIME = new TimerCounter();
    private static final TimerCounter PRELIMINARY_SURFACE_TIME = new TimerCounter();
    private static final TimerCounter STONE_DEPTH_TIME = new TimerCounter();
    private static final TimerCounter STONE_MASK_LOAD_TIME = new TimerCounter();
    private static final TimerCounter PROGRAM_APPLY_TIME = new TimerCounter();
    private static final TimerCounter FLUID_POSTPROCESS_TIME = new TimerCounter();
    private static final TimerCounter FROZEN_OCEAN_TIME = new TimerCounter();
    private static final TimerCounter FALLBACK_RULE_BRIDGE_TIME = new TimerCounter();
    private static final TimerCounter FALLBACK_CONDITION_BRIDGE_TIME = new TimerCounter();

    private static final ConcurrentHashMap<String, LongAdder> FALLBACK_RULE_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> FALLBACK_CONDITION_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TimerCounter> CONDITION_TIMES = new ConcurrentHashMap<>();

    private SurfaceMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    static void compiledProgram() {
        if (!ENABLED) return;
        COMPILED_PROGRAMS.increment();
    }

    static void irProgram() {
        if (!ENABLED) return;
        IR_PROGRAMS.increment();
    }

    static void irFallback() {
        if (!ENABLED) return;
        IR_FALLBACKS.increment();
    }

    static void irFallbackNodes(int ruleNodes, int conditionNodes) {
        if (!ENABLED) return;
        IR_FALLBACK_RULE_NODES.add(ruleNodes);
        IR_FALLBACK_CONDITION_NODES.add(conditionNodes);
    }

    static void interpretedProgram() {
        if (!ENABLED) return;
        INTERPRETED_PROGRAMS.increment();
    }

    public static void optimizedProgram() {
        if (!ENABLED) return;
        OPTIMIZED_PROGRAMS.increment();
    }

    public static void cacheHit() {
        if (!ENABLED) return;
        CACHE_HITS.increment();
    }

    public static void cacheMiss() {
        if (!ENABLED) return;
        CACHE_MISSES.increment();
    }

    public static void lastEntryHit() {
        if (!ENABLED) return;
        LAST_ENTRY_HITS.increment();
    }

    static void unsupportedProgram() {
        if (!ENABLED) return;
        UNSUPPORTED_PROGRAMS.increment();
    }

    static void unsupportedCacheHit() {
        if (!ENABLED) return;
        UNSUPPORTED_CACHE_HITS.increment();
    }

    public static void vanillaFallback() {
        if (!ENABLED) return;
        VANILLA_FALLBACKS.increment();
    }

    public static void sectionProcessed() {
        if (!ENABLED) return;
        SECTIONS_PROCESSED.increment();
    }

    public static void emptySectionSkipped() {
        if (!ENABLED) return;
        EMPTY_SECTIONS_SKIPPED.increment();
    }

    public static void rawBlockArrayMiss() {
        if (!ENABLED) return;
        RAW_BLOCK_ARRAY_MISSES.increment();
    }

    public static void stonelessSectionSkipped() {
        if (!ENABLED) return;
        STONELESS_SECTIONS_SKIPPED.increment();
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

    public static void recordCacheLookupTime(long startNanos) {
        CACHE_LOOKUP_TIME.record(startNanos);
    }

    public static void recordCompileTime(long startNanos) {
        COMPILE_TIME.record(startNanos);
    }

    public static void recordBiomePrepTime(long startNanos) {
        BIOME_PREP_TIME.record(startNanos);
    }

    public static void recordSurfaceDepthTime(long startNanos) {
        SURFACE_DEPTH_TIME.record(startNanos);
    }

    public static void recordSecondarySurfaceTime(long startNanos) {
        SECONDARY_SURFACE_TIME.record(startNanos);
    }

    public static void recordPreliminarySurfaceTime(long startNanos) {
        PRELIMINARY_SURFACE_TIME.record(startNanos);
    }

    public static void recordStoneDepthTime(long startNanos) {
        STONE_DEPTH_TIME.record(startNanos);
    }

    public static void recordStoneMaskLoadTime(long startNanos) {
        STONE_MASK_LOAD_TIME.record(startNanos);
    }

    public static void recordProgramApplyTime(long startNanos) {
        PROGRAM_APPLY_TIME.record(startNanos);
    }

    public static void recordFluidPostprocessTime(long startNanos) {
        FLUID_POSTPROCESS_TIME.record(startNanos);
    }

    public static void recordFrozenOceanTime(long startNanos) {
        FROZEN_OCEAN_TIME.record(startNanos);
    }

    static void recordFallbackRuleBridgeTime(long startNanos) {
        FALLBACK_RULE_BRIDGE_TIME.record(startNanos);
    }

    static void recordFallbackConditionBridgeTime(long startNanos) {
        FALLBACK_CONDITION_BRIDGE_TIME.record(startNanos);
    }

    static void recordConditionTime(String kind, long startNanos) {
        if (!ENABLED || startNanos == 0L) return;
        CONDITION_TIMES.computeIfAbsent(kind, ignored -> new TimerCounter()).record(startNanos);
    }

    public static long compiledPrograms() {
        return COMPILED_PROGRAMS.sum();
    }

    public static long irPrograms() {
        return IR_PROGRAMS.sum();
    }

    public static long irFallbacks() {
        return IR_FALLBACKS.sum();
    }

    public static long irFallbackRuleNodes() {
        return IR_FALLBACK_RULE_NODES.sum();
    }

    public static long irFallbackConditionNodes() {
        return IR_FALLBACK_CONDITION_NODES.sum();
    }

    public static long interpretedPrograms() {
        return INTERPRETED_PROGRAMS.sum();
    }

    public static long optimizedPrograms() {
        return OPTIMIZED_PROGRAMS.sum();
    }

    public static long cacheHits() {
        return CACHE_HITS.sum();
    }

    public static long cacheMisses() {
        return CACHE_MISSES.sum();
    }

    public static long lastEntryHits() {
        return LAST_ENTRY_HITS.sum();
    }

    public static long unsupportedPrograms() {
        return UNSUPPORTED_PROGRAMS.sum();
    }

    public static long unsupportedCacheHits() {
        return UNSUPPORTED_CACHE_HITS.sum();
    }

    public static long vanillaFallbacks() {
        return VANILLA_FALLBACKS.sum();
    }

    public static long sectionsProcessed() {
        return SECTIONS_PROCESSED.sum();
    }

    public static long emptySectionsSkipped() {
        return EMPTY_SECTIONS_SKIPPED.sum();
    }

    public static long rawBlockArrayMisses() {
        return RAW_BLOCK_ARRAY_MISSES.sum();
    }

    public static long stonelessSectionsSkipped() {
        return STONELESS_SECTIONS_SKIPPED.sum();
    }

    public static long fallbackIslands() {
        return FALLBACK_ISLANDS.sum();
    }

    public static long conditionCacheHits() {
        return CONDITION_CACHE_HITS.sum();
    }

    public static long conditionCacheMisses() {
        return CONDITION_CACHE_MISSES.sum();
    }

    public static long activeMaskEarlyExits() {
        return ACTIVE_MASK_EARLY_EXITS.sum();
    }

    public static void reset() {
        COMPILED_PROGRAMS.reset();
        IR_PROGRAMS.reset();
        IR_FALLBACKS.reset();
        IR_FALLBACK_RULE_NODES.reset();
        IR_FALLBACK_CONDITION_NODES.reset();
        INTERPRETED_PROGRAMS.reset();
        OPTIMIZED_PROGRAMS.reset();
        CACHE_HITS.reset();
        CACHE_MISSES.reset();
        LAST_ENTRY_HITS.reset();
        UNSUPPORTED_PROGRAMS.reset();
        UNSUPPORTED_CACHE_HITS.reset();
        VANILLA_FALLBACKS.reset();
        SECTIONS_PROCESSED.reset();
        EMPTY_SECTIONS_SKIPPED.reset();
        RAW_BLOCK_ARRAY_MISSES.reset();
        STONELESS_SECTIONS_SKIPPED.reset();
        FALLBACK_ISLANDS.reset();
        CONDITION_CACHE_HITS.reset();
        CONDITION_CACHE_MISSES.reset();
        ACTIVE_MASK_EARLY_EXITS.reset();
        CACHE_LOOKUP_TIME.reset();
        COMPILE_TIME.reset();
        BIOME_PREP_TIME.reset();
        SURFACE_DEPTH_TIME.reset();
        SECONDARY_SURFACE_TIME.reset();
        PRELIMINARY_SURFACE_TIME.reset();
        STONE_DEPTH_TIME.reset();
        STONE_MASK_LOAD_TIME.reset();
        PROGRAM_APPLY_TIME.reset();
        FLUID_POSTPROCESS_TIME.reset();
        FROZEN_OCEAN_TIME.reset();
        FALLBACK_RULE_BRIDGE_TIME.reset();
        FALLBACK_CONDITION_BRIDGE_TIME.reset();
        FALLBACK_RULE_CLASSES.clear();
        FALLBACK_CONDITION_CLASSES.clear();
        CONDITION_TIMES.clear();
    }

    public static long fallbackRuleClassCount(String className) {
        LongAdder counter = FALLBACK_RULE_CLASSES.get(className);
        return counter == null ? 0L : counter.sum();
    }

    public static long fallbackConditionClassCount(String className) {
        LongAdder counter = FALLBACK_CONDITION_CLASSES.get(className);
        return counter == null ? 0L : counter.sum();
    }

    public static long cacheLookupNanos() {
        return CACHE_LOOKUP_TIME.nanos();
    }

    public static long compileNanos() {
        return COMPILE_TIME.nanos();
    }

    public static long biomePrepNanos() {
        return BIOME_PREP_TIME.nanos();
    }

    public static long surfaceDepthNanos() {
        return SURFACE_DEPTH_TIME.nanos();
    }

    public static long secondarySurfaceNanos() {
        return SECONDARY_SURFACE_TIME.nanos();
    }

    public static long preliminarySurfaceNanos() {
        return PRELIMINARY_SURFACE_TIME.nanos();
    }

    public static long stoneDepthNanos() {
        return STONE_DEPTH_TIME.nanos();
    }

    public static long stoneMaskLoadNanos() {
        return STONE_MASK_LOAD_TIME.nanos();
    }

    public static long programApplyNanos() {
        return PROGRAM_APPLY_TIME.nanos();
    }

    public static long fluidPostprocessNanos() {
        return FLUID_POSTPROCESS_TIME.nanos();
    }

    public static long frozenOceanNanos() {
        return FROZEN_OCEAN_TIME.nanos();
    }

    public static long fallbackRuleBridgeNanos() {
        return FALLBACK_RULE_BRIDGE_TIME.nanos();
    }

    public static long fallbackConditionBridgeNanos() {
        return FALLBACK_CONDITION_BRIDGE_TIME.nanos();
    }

    public static long conditionEvalCount(String kind) {
        TimerCounter counter = CONDITION_TIMES.get(kind);
        return counter == null ? 0L : counter.count();
    }

    public static long conditionEvalNanos(String kind) {
        TimerCounter counter = CONDITION_TIMES.get(kind);
        return counter == null ? 0L : counter.nanos();
    }

    public static Set<String> conditionKinds() {
        return CONDITION_TIMES.keySet();
    }

    private static final class TimerCounter {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        void record(long startNanos) {
            if (!ENABLED || startNanos == 0L) return;
            this.count.increment();
            this.nanos.add(System.nanoTime() - startNanos);
        }

        long count() {
            return this.count.sum();
        }

        long nanos() {
            return this.nanos.sum();
        }

        void reset() {
            this.count.reset();
            this.nanos.reset();
        }
    }
}
