package dev.sixik.generator_accelerator.common.beardifier;

public final class BeardifierStats {
    public static volatile boolean ENABLED;

    private BeardifierStats() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static void reset() {
        ENABLED = false;
    }
}
