package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks features that broke the optimized decoration pipeline so they can be
 * routed through a conservative vanilla-style path for the rest of the session.
 */
final class DecorationPipelineCompatibility {
    private static final Map<PlacedFeature, Boolean> QUARANTINED_FEATURES = new IdentityHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> QUARANTINED_NAMESPACES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> DESCRIPTOR_FAILURES = new ConcurrentHashMap<>();

    private DecorationPipelineCompatibility() {
    }

    static boolean shouldUseSafeVanilla(@Nullable PlacedFeature feature) {
        if (feature == null) {
            return false;
        }
        synchronized (QUARANTINED_FEATURES) {
            return QUARANTINED_FEATURES.containsKey(feature);
        }
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
        synchronized (QUARANTINED_FEATURES) {
            firstFailure = QUARANTINED_FEATURES.put(feature, Boolean.TRUE) == null;
        }
        if (!firstFailure) {
            return;
        }

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
