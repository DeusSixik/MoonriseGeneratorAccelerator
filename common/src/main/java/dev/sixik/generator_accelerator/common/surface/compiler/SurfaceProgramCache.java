package dev.sixik.generator_accelerator.common.surface.compiler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.world.level.levelgen.SurfaceRules;

public final class SurfaceProgramCache {
    private static final Cache<SurfaceRules.RuleSource, SurfaceProgram> CACHE = Caffeine.newBuilder()
            .initialCapacity(64)
            .weakKeys()
            .build();

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

        SurfaceProgram cached = CACHE.getIfPresent(ruleSource);
        if (cached != null) {
            SurfaceMetrics.cacheHit();
            SurfaceMetrics.recordCacheLookupTime(lookupStart);
            lastEntry = new LastEntry(ruleSource, cached);
            return cached;
        }

        SurfaceMetrics.cacheMiss();
        SurfaceMetrics.recordCacheLookupTime(lookupStart);

        cached = CACHE.get(ruleSource, SurfaceRuleCompiler::compile);
        lastEntry = new LastEntry(ruleSource, cached);
        return cached;
    }

    public static void clear() {
        CACHE.invalidateAll();
        CACHE.cleanUp();
        lastEntry = null;
    }

    private record LastEntry(SurfaceRules.RuleSource ruleSource, SurfaceProgram program) {
    }
}
