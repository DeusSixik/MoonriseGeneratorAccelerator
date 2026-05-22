package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.common.worldgen.parallel.GACustomChunkGraphScheduler;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkMapAccessor;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = ChunkMap.class, priority = 700)
public abstract class MixinChunkMap$custom_graph_scheduler {

    @Unique
    private static final ThreadLocal<ChunkGenerationTask[]> GA$SHEDULED_TASKS =
            ThreadLocal.withInitial(() -> new ChunkGenerationTask[64]);

    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void ga$runGenerationTasksWithCustomGraph(CallbackInfo ci) {
        if (!GACustomChunkGraphScheduler.enabled() || GACustomChunkGraphScheduler.shutdownRequested()) {
            return;
        }

        List<ChunkGenerationTask> pending =
                ((MixinChunkMapAccessor) (Object) this).ga$getPendingGenerationTasks();

        int size = pending.size();
        if (size == 0) {
            ci.cancel();
            return;
        }

        ChunkGenerationTask[] tasks = GA$SHEDULED_TASKS.get();
        if (tasks.length < size) {
            tasks = new ChunkGenerationTask[Math.max(size, tasks.length + (tasks.length >> 1))];
            GA$SHEDULED_TASKS.set(tasks);
        }

        pending.toArray(tasks);
        pending.clear();

        ChunkMap chunkMap = (ChunkMap) (Object) this;

        for (int i = 0; i < size; i++) {
            GACustomChunkGraphScheduler.schedule(chunkMap, tasks[i]);
            tasks[i] = null;
        }

        ci.cancel();
    }
}
