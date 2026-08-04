package dev.sixik.generator_accelerator.common.surface_compiler.cache;

import java.util.Objects;

public record FingerprintCacheKey(
        String structuralRuleHash,
        String minecraftVersion,
        String generatorAcceleratorVersion,
        long datapackEpoch,
        String adapterRegistryHash,
        String runtimeBindingVersion,
        String optimizationProfile,
        String safetyMode
) {
}
