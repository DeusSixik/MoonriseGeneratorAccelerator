package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChunkMap.class)
public interface MixinChunkMapAccessor {
    @Accessor("pendingGenerationTasks")
    List<ChunkGenerationTask> ga$getPendingGenerationTasks();

    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> ga$getMainThreadExecutor();

    @Invoker("runGenerationTask")
    void ga$runGenerationTask(ChunkGenerationTask task);
}
