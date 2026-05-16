package dev.sixik.generator_accelerator.common.features.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks features that broke the optimized decoration pipeline so they can be
 * routed through a conservative vanilla-style path for the rest of the session.
 */
public final class DecorationPipelineCompatibility {
    private static final int TREE_LIKE_SCAN_DEPTH = 8;
    private static final Cache<PlacedFeature, Boolean> TREE_LIKE_FEATURES = Caffeine.newBuilder()
            .initialCapacity(64)
            .weakKeys()
            .build();
    private static final Cache<PlacedFeature, Boolean> QUARANTINED_FEATURES = Caffeine.newBuilder()
            .initialCapacity(32)
            .weakKeys()
            .build();
    private static final ConcurrentHashMap<String, AtomicInteger> QUARANTINED_NAMESPACES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> DESCRIPTOR_FAILURES = new ConcurrentHashMap<>();
    private static final AtomicInteger QUARANTINED_FEATURE_COUNT = new AtomicInteger();

    private DecorationPipelineCompatibility() {
    }

    public static void clearSessionCaches() {
        TREE_LIKE_FEATURES.invalidateAll();
        TREE_LIKE_FEATURES.cleanUp();
        QUARANTINED_FEATURES.invalidateAll();
        QUARANTINED_FEATURES.cleanUp();
        QUARANTINED_FEATURE_COUNT.set(0);
        QUARANTINED_NAMESPACES.clear();
        DESCRIPTOR_FAILURES.clear();
    }

    public static boolean isTreeLikeFeature(@Nullable PlacedFeature feature) {
        if (feature == null) {
            return false;
        }
        return Boolean.TRUE.equals(TREE_LIKE_FEATURES.get(feature, placedFeature ->
                isTreeLikePlacedFeature(placedFeature, 0)));
    }

    static boolean shouldUseSafeVanilla(@Nullable PlacedFeature feature) {
        if (isTreeLikeFeature(feature)) {
            return true;
        }
        if (QUARANTINED_FEATURE_COUNT.get() == 0) {
            return false;
        }
        return QUARANTINED_FEATURES.getIfPresent(feature) != null;
    }

    private static boolean isTreeLikePlacedFeature(@Nullable PlacedFeature feature, int depth) {
        if (feature == null || depth > TREE_LIKE_SCAN_DEPTH) {
            return false;
        }
        try {
            Holder<ConfiguredFeature<?, ?>> holder = feature.feature();
            return holder != null && isTreeLikeConfiguredFeature(holder.value(), depth + 1);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isTreeLikeConfiguredFeature(@Nullable ConfiguredFeature<?, ?> configuredFeature, int depth) {
        if (configuredFeature == null || depth > TREE_LIKE_SCAN_DEPTH) {
            return false;
        }

        Feature<?> feature = configuredFeature.feature();
        if (feature == Feature.TREE) {
            return true;
        }

        Object config = configuredFeature.config();
        if (config instanceof RandomPatchConfiguration randomPatch) {
            return isTreeLikePlacedFeature(randomPatch.feature().value(), depth + 1);
        }
        if (config instanceof RandomFeatureConfiguration randomFeature) {
            for (WeightedPlacedFeature entry : randomFeature.features) {
                if (isTreeLikePlacedFeature(entry.feature.value(), depth + 1)) {
                    return true;
                }
            }
            return isTreeLikePlacedFeature(randomFeature.defaultFeature.value(), depth + 1);
        }
        if (config instanceof RandomBooleanFeatureConfiguration randomBoolean) {
            return isTreeLikePlacedFeature(randomBoolean.featureTrue.value(), depth + 1)
                    || isTreeLikePlacedFeature(randomBoolean.featureFalse.value(), depth + 1);
        }
        if (config instanceof SimpleRandomFeatureConfiguration simpleRandom) {
            HolderSet<PlacedFeature> features = simpleRandom.features;
            for (int i = 0, size = features.size(); i < size; i++) {
                if (isTreeLikePlacedFeature(features.get(i).value(), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void quarantine(
            @Nullable Registry<PlacedFeature> registry,
            @Nullable PlacedFeature feature,
            DecorationKernelPlan kernel,
            int step,
            int featureIndex,
            Throwable failure
    ) {
        if (feature == null) {
            GeneratorAccelerator.LOGGER.warn(
                    "GA decoration pipeline hit {} at step {} feature {} kernel {} without a fallback feature; rethrowing.",
                    failure.getClass().getSimpleName(),
                    step,
                    featureIndex,
                    kernel.kind(),
                    failure
            );
            return;
        }

        boolean firstFailure;
        firstFailure = QUARANTINED_FEATURES.asMap().putIfAbsent(feature, Boolean.TRUE) == null;
        if (!firstFailure) {
            return;
        }
        QUARANTINED_FEATURE_COUNT.incrementAndGet();

        String featureId = featureId(registry, feature);
        String namespace = namespace(featureId);
        int namespaceCount = QUARANTINED_NAMESPACES
                .computeIfAbsent(namespace, ignored -> new AtomicInteger())
                .incrementAndGet();

        GeneratorAccelerator.LOGGER.warn(
                "GA decoration pipeline quarantined placed feature {} (namespace={}, namespaceFailures={}, step={}, featureIndex={}, originalFeatureIndex={}, kernel={}, configured={}, modifiers=[{}]). "
                        + "The feature will use the safe vanilla path for the rest of this session; this usually means a missing compat redirect or a pipeline-only assumption for that mod.",
                featureId,
                namespace,
                namespaceCount,
                step,
                featureIndex,
                kernel.originalFeatureIndex(),
                kernel.kind(),
                configuredFeatureName(kernel),
                placementSummary(feature),
                failure
        );
    }

    static boolean shouldLogDescriptorFailure(Throwable failure) {
        String key = failure.getClass().getName() + ":" + String.valueOf(failure.getMessage());
        return DESCRIPTOR_FAILURES.putIfAbsent(key, Boolean.TRUE) == null;
    }

    private static String configuredFeatureName(DecorationKernelPlan kernel) {
        Holder<ConfiguredFeature<?, ?>> holder = kernel.configuredFeature();
        if (holder == null) {
            return "unknown";
        }
        ConfiguredFeature<?, ?> configuredFeature = holder.value();
        if (configuredFeature == null) {
            return "unknown";
        }
        Feature<?> feature = configuredFeature.feature();
        return feature == null ? "unknown" : feature.getClass().getName();
    }

    private static String featureId(@Nullable Registry<PlacedFeature> registry, PlacedFeature feature) {
        if (registry != null) {
            ResourceKey<PlacedFeature> resourceKey = registry.getResourceKey(feature).orElse(null);
            if (resourceKey != null) {
                return resourceKey.location().toString();
            }
        }
        return feature.toString();
    }

    private static String namespace(String featureId) {
        ResourceLocation location = ResourceLocation.tryParse(featureId);
        return location == null ? "unknown" : location.getNamespace();
    }

    private static String placementSummary(PlacedFeature feature) {
        List<PlacementModifier> placement = feature.placement();
        if (placement.isEmpty()) {
            return "none";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < placement.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(placement.get(i).getClass().getSimpleName());
        }
        return builder.toString();
    }
}
