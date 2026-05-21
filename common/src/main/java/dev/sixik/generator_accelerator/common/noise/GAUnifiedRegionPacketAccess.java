package dev.sixik.generator_accelerator.common.noise;

import dev.sixik.generator_accelerator.common.worldgen.region.GAUnifiedRegionPacket;

public interface GAUnifiedRegionPacketAccess {
    GAUnifiedRegionPacket ga$unifiedRegionPacket();

    default void ga$requestRegionalNoisePrewarm() {
    }

    default void ga$ensureRegionalNoiseReady() {
    }
}
