package dev.sixik.generator_accelerator.common.chunks.compats.modernfix;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.Executor;

public interface GAISuspendedHolderTrackingChunkMap {
    void ga$markForSuspensionCheck(ChunkPos pos);

    Executor ga$getMainThreadExecutor();
}
