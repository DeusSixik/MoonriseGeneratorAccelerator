package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

final class MinecraftBootstrapHelper {
    private static boolean bootstrapped;

    private MinecraftBootstrapHelper() {
    }

    static synchronized void ensureBootstrapped() {
        if (bootstrapped) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
