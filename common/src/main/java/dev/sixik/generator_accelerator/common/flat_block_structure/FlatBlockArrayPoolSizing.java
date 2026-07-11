package dev.sixik.generator_accelerator.common.flat_block_structure;

import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;

public final class FlatBlockArrayPoolSizing {
    private static final int SECTIONS_PER_WORKSPACE = 24;

    private FlatBlockArrayPoolSizing() {
    }

    public static int defaultRawPoolMax() {
        GAConfig config = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
        return defaultRawPoolMax(Runtime.getRuntime().availableProcessors(), config.maxInFlightWorkspaces);
    }

    public static int defaultRawPoolMax(int processors, int configuredMaxInFlightWorkspaces) {
        int safeProcessors = Math.max(1, processors);
        int maxInFlight = configuredMaxInFlightWorkspaces > 0
                ? configuredMaxInFlightWorkspaces
                : Math.max(1, safeProcessors / 2);
        long desired = (long) maxInFlight * SECTIONS_PER_WORKSPACE;
        return (int) Math.max(32L, Math.min(1024L, desired));
    }

    public static int defaultDirtyPoolMax(int rawPoolMax) {
        return Math.max(32, Math.min(256, rawPoolMax));
    }

    public static int defaultPrealloc(int poolMax) {
        return Math.min(8, Math.max(0, poolMax));
    }

    public static int defaultDirtyIndexCapacity() {
        return 256;
    }
}
