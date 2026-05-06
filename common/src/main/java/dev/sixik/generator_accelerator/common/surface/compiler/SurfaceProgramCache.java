package dev.sixik.generator_accelerator.common.surface.compiler;

import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SurfaceProgramCache {
    private static final Map<SurfaceRules.RuleSource, SurfaceProgram> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile LastEntry lastEntry;

    private SurfaceProgramCache() {
    }

    public static SurfaceProgram getOrCompile(SurfaceRules.RuleSource ruleSource) {
        long lookupStart = SurfaceMetrics.startTimer();
        LastEntry last = lastEntry;
        if (last != null && last.ruleSource == ruleSource) {
            SurfaceMetrics.lastEntryHit();
            SurfaceMetrics.cacheHit();
            SurfaceMetrics.recordCacheLookupTime(lookupStart);
            return last.program;
        }

        SurfaceProgram cached;
        synchronized (CACHE) {
            cached = CACHE.get(ruleSource);
            if (cached != null) {
                SurfaceMetrics.cacheHit();
                SurfaceMetrics.recordCacheLookupTime(lookupStart);
                lastEntry = new LastEntry(ruleSource, cached);
                return cached;
            }
        }

        SurfaceMetrics.cacheMiss();
        SurfaceMetrics.recordCacheLookupTime(lookupStart);

        SurfaceProgram compiled = SurfaceRuleCompiler.compile(ruleSource);
        synchronized (CACHE) {
            cached = CACHE.get(ruleSource);
            if (cached == null) {
                cached = compiled;
                CACHE.put(ruleSource, cached);
            }
        }

        lastEntry = new LastEntry(ruleSource, cached);
        return cached;
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        lastEntry = null;
    }

    private record LastEntry(SurfaceRules.RuleSource ruleSource, SurfaceProgram program) {
    }
}
