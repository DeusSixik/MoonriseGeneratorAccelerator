package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.common.worldgen.parallel.GACustomChunkGraphScheduler;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkMapAccessor;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = ChunkMap.class, priority = 700)
public abstract class MixinChunkMap$custom_graph_scheduler {
    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void ga$runGenerationTasksWithCustomGraph(CallbackInfo ci) {
        if (!GACustomChunkGraphScheduler.canInterceptGenerationTasks()) {
            return;
        }

        List<ChunkGenerationTask> pending =
                ((MixinChunkMapAccessor) (Object) this).ga$getPendingGenerationTasks();
        if (pending.isEmpty()) {
            ci.cancel();
            return;
        }

        ArrayList<ChunkGenerationTask> tasks = new ArrayList<>(pending);
        pending.clear();
        ChunkMap chunkMap = (ChunkMap) (Object) this;
        for (ChunkGenerationTask task : tasks) {
            if (!GACustomChunkGraphScheduler.schedule(chunkMap, task)) {
                pending.add(task);
            }
        }
        ci.cancel();
    }
}
