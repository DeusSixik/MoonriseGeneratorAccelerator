package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.List;
import java.util.Set;

public record WorldgenUnitProfile(
        String id,
        String namespace,
        String className,
        String bytecodeHash,
        String configHash,
        long registryEpoch,
        String entryPointMethod,
        int estimatedCost,
        Set<WorldgenEffectFlag> effectFlags,
        WorldgenSafetyTier safetyTier,
        List<String> guards,
        String fallbackReason
) {
    public boolean hasEffect(WorldgenEffectFlag flag) {
        return this.effectFlags.contains(flag);
    }

    public WorldgenProfileRolloutMetadata rolloutMetadata() {
        return WorldgenProfileRolloutMetadata.from(this);
    }
}
