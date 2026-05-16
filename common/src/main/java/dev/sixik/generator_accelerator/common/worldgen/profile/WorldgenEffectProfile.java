package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.List;
import java.util.Set;

public record WorldgenEffectProfile(
        String className,
        String methodHint,
        String fingerprint,
        Set<WorldgenEffectFlag> effectFlags,
        List<String> unsafeReasons,
        boolean readable,
        boolean budgetExceeded,
        int scannedConstants,
        int scannedMethods
) {
    public WorldgenEffectProfile {
        className = className == null ? "" : className;
        methodHint = methodHint == null ? "" : methodHint;
        fingerprint = fingerprint == null ? "" : fingerprint;
        effectFlags = effectFlags == null ? Set.of() : Set.copyOf(effectFlags);
        unsafeReasons = unsafeReasons == null ? List.of() : List.copyOf(unsafeReasons);
    }

    public boolean hasEffect(WorldgenEffectFlag flag) {
        return this.effectFlags.contains(flag);
    }

    public boolean hasHardUnsafeEffect() {
        return hasEffect(WorldgenEffectFlag.USES_REFLECTION)
                || hasEffect(WorldgenEffectFlag.USES_NATIVE)
                || hasEffect(WorldgenEffectFlag.USES_IO)
                || hasEffect(WorldgenEffectFlag.USES_THREADS)
                || hasEffect(WorldgenEffectFlag.USES_SYNCHRONIZED)
                || hasEffect(WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE)
                || hasEffect(WorldgenEffectFlag.CROSS_CHUNK_WRITE);
    }

    public String reasonSummary() {
        if (this.unsafeReasons.isEmpty()) {
            return this.readable ? "" : "effect scan unreadable";
        }
        return String.join(", ", this.unsafeReasons);
    }
}
