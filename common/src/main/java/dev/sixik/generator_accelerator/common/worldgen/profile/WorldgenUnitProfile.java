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
    public WorldgenUnitProfile {
        id = id == null ? "" : id;
        namespace = namespace == null ? "" : namespace;
        className = className == null ? "" : className;
        bytecodeHash = bytecodeHash == null ? "" : bytecodeHash;
        configHash = configHash == null ? "" : configHash;
        entryPointMethod = entryPointMethod == null ? "" : entryPointMethod;
        effectFlags = effectFlags == null ? Set.of() : Set.copyOf(effectFlags);
        safetyTier = safetyTier == null ? WorldgenSafetyTier.SERIAL_ISOLATED : safetyTier;
        guards = guards == null ? List.of() : List.copyOf(guards);
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
    }

    public boolean hasEffect(WorldgenEffectFlag flag) {
        return this.effectFlags.contains(flag);
    }

    public WorldgenProfileRolloutMetadata rolloutMetadata() {
        return WorldgenProfileRolloutMetadata.from(this);
    }
}
