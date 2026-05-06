package dev.sixik.generator_accelerator.common.surface.compiler;

import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SurfaceProgramCache {
    private static final Map<SurfaceRules.RuleSource, SurfaceProgram> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private SurfaceProgramCache() {
    }

    public static SurfaceProgram getOrCompile(SurfaceRules.RuleSource ruleSource) {
        SurfaceProgram cached = CACHE.get(ruleSource);
        if (cached != null) {
            return cached;
        }

        SurfaceProgram compiled = SurfaceRuleCompiler.compile(ruleSource);
        CACHE.put(ruleSource, compiled);
        return compiled;
    }

    public static void clear() {
        CACHE.clear();
    }
}
