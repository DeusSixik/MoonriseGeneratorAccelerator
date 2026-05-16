package dev.sixik.generator_accelerator.common.surface.compiler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.common.surface.exceptions.UnknownSurfaceConditionSource;
import dev.sixik.generator_accelerator.common.surface.exceptions.UnknownSurfaceRuleSource;
import net.minecraft.world.level.levelgen.SurfaceRules;

public final class SurfaceProgramCache {
    private static final Cache<SurfaceRules.RuleSource, SurfaceProgram> CACHE = Caffeine.newBuilder()
            .initialCapacity(64)
            .weakKeys()
            .build();
    private static final Cache<SurfaceRules.RuleSource, Boolean> UNSUPPORTED = Caffeine.newBuilder()
            .initialCapacity(16)
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

    public static SurfaceProgram tryGetOrCompile(SurfaceRules.RuleSource ruleSource) {
        if (isUnsupported(ruleSource)) {
            SurfaceMetrics.unsupportedCacheHit();
            return null;
        }
        try {
            return getOrCompile(ruleSource);
        } catch (RuntimeException failure) {
            if (!isUnsupportedFailure(failure)) {
                throw failure;
            }
            markUnsupported(ruleSource);
            SurfaceMetrics.unsupportedProgram();
            return null;
        }
    }

    public static boolean isUnsupported(SurfaceRules.RuleSource ruleSource) {
        return ruleSource != null && UNSUPPORTED.getIfPresent(ruleSource) != null;
    }

    public static void markUnsupported(SurfaceRules.RuleSource ruleSource) {
        if (ruleSource == null) {
            return;
        }
        UNSUPPORTED.put(ruleSource, Boolean.TRUE);
        CACHE.invalidate(ruleSource);
        LastEntry last = lastEntry;
        if (last != null && last.ruleSource == ruleSource) {
            lastEntry = null;
        }
    }

    public static void clear() {
        CACHE.invalidateAll();
        UNSUPPORTED.invalidateAll();
        CACHE.cleanUp();
        UNSUPPORTED.cleanUp();
        lastEntry = null;
    }

    private static boolean isUnsupportedFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnknownSurfaceRuleSource || current instanceof UnknownSurfaceConditionSource) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record LastEntry(SurfaceRules.RuleSource ruleSource, SurfaceProgram program) {
    }
}
