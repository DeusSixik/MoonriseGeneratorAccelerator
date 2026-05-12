package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenEffectFlag;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenSafetyTier;
import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenUnitProfile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WorldgenPatternRecognizer {
    private static final Set<WorldgenEffectFlag> HARD_UNSAFE = EnumSet.of(
            WorldgenEffectFlag.USES_REFLECTION,
            WorldgenEffectFlag.USES_NATIVE,
            WorldgenEffectFlag.USES_IO,
            WorldgenEffectFlag.USES_THREADS,
            WorldgenEffectFlag.USES_SYNCHRONIZED,
            WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE,
            WorldgenEffectFlag.CROSS_CHUNK_WRITE
    );

    public Optional<WorldgenGeneratedPlan> recognize(WorldgenUnitProfile profile) {
        if (profile == null || hasHardUnsafe(profile)) {
            return Optional.empty();
        }

        String haystack = searchable(profile);
        WorldgenOptimizationPattern pattern = patternFor(profile, haystack);
        if (pattern == WorldgenOptimizationPattern.NONE) {
            return Optional.empty();
        }

        WorldgenFastPathKind kind = fastPathKind(profile, pattern);
        WorldgenSafetyTier targetTier = targetTier(profile, pattern);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("className", profile.className());
        attributes.put("namespace", profile.namespace());
        attributes.put("entryPoint", profile.entryPointMethod());
        attributes.put("patternSource", haystack);

        return Optional.of(new WorldgenGeneratedPlan(
                profile.id(),
                pattern,
                kind,
                targetTier,
                guards(profile),
                attributes,
                Math.max(0, profile.estimatedCost()),
                "recognized " + pattern.name().toLowerCase(Locale.ROOT) + " as " + kind.name().toLowerCase(Locale.ROOT)
        ));
    }

    public boolean hasHardUnsafe(WorldgenUnitProfile profile) {
        for (WorldgenEffectFlag flag : HARD_UNSAFE) {
            if (profile.hasEffect(flag)) {
                return true;
            }
        }
        return false;
    }

    private WorldgenOptimizationPattern patternFor(WorldgenUnitProfile profile, String haystack) {
        if (profile.hasEffect(WorldgenEffectFlag.PURE)
                && !profile.hasEffect(WorldgenEffectFlag.WRITES_BLOCKS)
                && containsAny(haystack, "density", "surface", "climate", "biome")) {
            return WorldgenOptimizationPattern.PURE_DENSITY_OR_SURFACE;
        }
        if (containsAny(haystack, "ore", "scattered_ore")) return WorldgenOptimizationPattern.ORE_LIKE;
        if (containsAny(haystack, "disk", "blob")) return WorldgenOptimizationPattern.DISK_OR_BLOB_LIKE;
        if (containsAny(haystack, "random_patch", "patch")) return WorldgenOptimizationPattern.RANDOM_PATCH;
        if (containsAny(haystack, "simple_block")) return WorldgenOptimizationPattern.SIMPLE_BLOCK;
        if (containsAny(haystack, "spring", "liquid", "fluid")) return WorldgenOptimizationPattern.SPRING_OR_LIQUID;
        if (containsAny(haystack, "vegetation", "plant", "kelp", "seagrass", "water_plant")) {
            return WorldgenOptimizationPattern.VEGETATION_OR_WATER_PLANT;
        }
        if (containsAny(haystack, "selector", "random_selector")) return WorldgenOptimizationPattern.SELECTOR;
        if (containsAny(haystack, "height_range", "in_square", "placement", "placementmodifier", "filter")) {
            return WorldgenOptimizationPattern.PLACEMENT_CHAIN;
        }
        if (containsAny(haystack, "predicate", "blockpredicate")) return WorldgenOptimizationPattern.SIMPLE_BLOCK_PREDICATE;
        if (profile.hasEffect(WorldgenEffectFlag.READS_BLOCKS) && containsAny(haystack, "neighbor", "neighbour", "nearby")) {
            return WorldgenOptimizationPattern.NEIGHBORHOOD_BLOCK_CHECK;
        }
        if (profile.hasEffect(WorldgenEffectFlag.STREAM_HEAVY) && containsAny(haystack, "blockpos", "position", "stream")) {
            return WorldgenOptimizationPattern.STREAM_POSITION_PIPELINE;
        }
        return WorldgenOptimizationPattern.NONE;
    }

    private WorldgenFastPathKind fastPathKind(WorldgenUnitProfile profile, WorldgenOptimizationPattern pattern) {
        if (pattern == WorldgenOptimizationPattern.PURE_DENSITY_OR_SURFACE && profile.estimatedCost() >= 4096) {
            return WorldgenFastPathKind.NATIVE_VECTOR;
        }
        if ((profile.hasEffect(WorldgenEffectFlag.STREAM_HEAVY) || profile.hasEffect(WorldgenEffectFlag.ALLOC_HEAVY))
                && profile.estimatedCost() >= 64) {
            return WorldgenFastPathKind.GENERATED_JAVA;
        }
        return WorldgenFastPathKind.DATA_PLAN;
    }

    private WorldgenSafetyTier targetTier(WorldgenUnitProfile profile, WorldgenOptimizationPattern pattern) {
        if (pattern == WorldgenOptimizationPattern.PURE_DENSITY_OR_SURFACE) {
            return WorldgenSafetyTier.PURE_READ_ONLY;
        }
        if (profile.hasEffect(WorldgenEffectFlag.WRITES_BLOCKS)) {
            return WorldgenSafetyTier.GA_NATIVE_DETERMINISTIC_WRITES;
        }
        return WorldgenSafetyTier.PARTIAL_NATIVE_VANILLA_FEATURE;
    }

    private List<WorldgenOptimizerGuard> guards(WorldgenUnitProfile profile) {
        List<WorldgenOptimizerGuard> guards = new ArrayList<>();
        guards.add(new WorldgenOptimizerGuard("className", profile.className(), true));
        guards.add(new WorldgenOptimizerGuard("bytecodeHash", profile.bytecodeHash(), !profile.bytecodeHash().isBlank()));
        guards.add(new WorldgenOptimizerGuard("configHash", profile.configHash(), !profile.configHash().isBlank()));
        guards.add(new WorldgenOptimizerGuard("registryEpoch", Long.toString(profile.registryEpoch()), true));
        guards.add(new WorldgenOptimizerGuard("entryPointMethod", profile.entryPointMethod(), !profile.entryPointMethod().isBlank()));
        return guards;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String searchable(WorldgenUnitProfile profile) {
        return (profile.id() + " " + profile.className() + " " + profile.entryPointMethod())
                .toLowerCase(Locale.ROOT);
    }
}
