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
    public FingerprintCacheKey {
        Objects.requireNonNull(structuralRuleHash, "structuralRuleHash");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(generatorAcceleratorVersion, "generatorAcceleratorVersion");
        Objects.requireNonNull(adapterRegistryHash, "adapterRegistryHash");
        Objects.requireNonNull(runtimeBindingVersion, "runtimeBindingVersion");
        Objects.requireNonNull(optimizationProfile, "optimizationProfile");
        Objects.requireNonNull(safetyMode, "safetyMode");
    }
}
