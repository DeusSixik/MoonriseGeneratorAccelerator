package dev.sixik.generator_accelerator.common.worldgen.profile;

public enum WorldgenSafetyTier {
    PURE_READ_ONLY(0),
    GA_NATIVE_DETERMINISTIC_WRITES(1),
    PARTIAL_NATIVE_VANILLA_FEATURE(2),
    TRANSACTIONAL_UNKNOWN(3),
    SERIAL_ISOLATED(4),
    VANILLA_FALLBACK_DISABLED(5);

    private final int id;

    WorldgenSafetyTier(int id) {
        this.id = id;
    }

    public int id() {
        return this.id;
    }
}
