package dev.sixik.generator_accelerator.common.worldgen.profile;

import java.util.EnumSet;
import java.util.List;

/** Conservative rollout metadata derived from a cheap worldgen profile. */
public record WorldgenProfileRolloutMetadata(
        WorldgenRolloutLane lane,
        WorldgenSafetyTier effectiveTier,
        boolean optimizedAllowed,
        boolean requiresTransactionSandbox,
        boolean requiresSerialLane,
        boolean disabled,
        List<String> guards,
        String reason
) {
    public WorldgenProfileRolloutMetadata {
        effectiveTier = effectiveTier == null ? WorldgenSafetyTier.SERIAL_ISOLATED : effectiveTier;
        lane = lane == null ? WorldgenRolloutLane.SERIAL : lane;
        guards = guards == null ? List.of() : List.copyOf(guards);
        reason = reason == null ? "" : reason;
    }

    public static WorldgenProfileRolloutMetadata from(WorldgenUnitProfile profile) {
        if (profile == null) {
            return disabled("null profile");
        }
        if (profile.safetyTier() == WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED) {
            return disabled(profile.fallbackReason());
        }
        EnumSet<WorldgenEffectFlag> flags = profile.effectFlags().isEmpty()
                ? EnumSet.noneOf(WorldgenEffectFlag.class)
                : EnumSet.copyOf(profile.effectFlags());
        if (hasHardUnsafeEffect(flags)) {
            return new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.SERIAL,
                    moreConservative(profile.safetyTier(), WorldgenSafetyTier.SERIAL_ISOLATED),
                    false,
                    false,
                    true,
                    false,
                    profile.guards(),
                    append(profile.fallbackReason(), "hard unsafe effect keeps unit serial")
            );
        }

        return switch (profile.safetyTier()) {
            case PURE_READ_ONLY -> new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.PURE_COMPUTE,
                    profile.safetyTier(),
                    true,
                    false,
                    false,
                    false,
                    profile.guards(),
                    profile.fallbackReason()
            );
            case GA_NATIVE_DETERMINISTIC_WRITES -> new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.NATIVE_JOURNAL,
                    profile.safetyTier(),
                    true,
                    false,
                    false,
                    false,
                    profile.guards(),
                    profile.fallbackReason()
            );
            case PARTIAL_NATIVE_VANILLA_FEATURE -> new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.PARTIAL_NATIVE,
                    profile.safetyTier(),
                    true,
                    false,
                    false,
                    false,
                    profile.guards(),
                    profile.fallbackReason()
            );
            case TRANSACTIONAL_UNKNOWN -> transactional(profile);
            case SERIAL_ISOLATED -> new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.SERIAL,
                    profile.safetyTier(),
                    false,
                    false,
                    true,
                    false,
                    profile.guards(),
                    profile.fallbackReason()
            );
            case VANILLA_FALLBACK_DISABLED -> disabled(profile.fallbackReason());
        };
    }

    public enum WorldgenRolloutLane {
        PURE_COMPUTE,
        NATIVE_JOURNAL,
        PARTIAL_NATIVE,
        TRANSACTIONAL,
        SERIAL,
        DISABLED
    }

    private static WorldgenProfileRolloutMetadata transactional(WorldgenUnitProfile profile) {
        if (!profile.guards().contains("transaction sandbox required")) {
            return new WorldgenProfileRolloutMetadata(
                    WorldgenRolloutLane.SERIAL,
                    WorldgenSafetyTier.SERIAL_ISOLATED,
                    false,
                    false,
                    true,
                    false,
                    profile.guards(),
                    append(profile.fallbackReason(), "missing transaction sandbox guard")
            );
        }
        return new WorldgenProfileRolloutMetadata(
                WorldgenRolloutLane.TRANSACTIONAL,
                profile.safetyTier(),
                true,
                true,
                false,
                false,
                profile.guards(),
                profile.fallbackReason()
        );
    }

    private static WorldgenProfileRolloutMetadata disabled(String reason) {
        return new WorldgenProfileRolloutMetadata(
                WorldgenRolloutLane.DISABLED,
                WorldgenSafetyTier.VANILLA_FALLBACK_DISABLED,
                false,
                false,
                false,
                true,
                List.of(),
                reason
        );
    }

    private static boolean hasHardUnsafeEffect(EnumSet<WorldgenEffectFlag> flags) {
        return flags.contains(WorldgenEffectFlag.USES_REFLECTION)
                || flags.contains(WorldgenEffectFlag.USES_NATIVE)
                || flags.contains(WorldgenEffectFlag.USES_IO)
                || flags.contains(WorldgenEffectFlag.USES_THREADS)
                || flags.contains(WorldgenEffectFlag.USES_SYNCHRONIZED)
                || flags.contains(WorldgenEffectFlag.USES_GLOBAL_MUTABLE_STATE)
                || flags.contains(WorldgenEffectFlag.CROSS_CHUNK_WRITE);
    }

    private static WorldgenSafetyTier moreConservative(WorldgenSafetyTier first, WorldgenSafetyTier second) {
        return first.id() >= second.id() ? first : second;
    }

    private static String append(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "; " + second;
    }
}
