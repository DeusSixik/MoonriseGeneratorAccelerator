package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GAChunkStatusPipeline;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceRuntime;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ChunkStatusTasks.class, priority = 700)
public abstract class MixinChunkStatusTasks$parallel_pipeline {
    @WrapMethod(method = "generateNoise")
    private static CompletableFuture<ChunkAccess> ga$parallelNoise(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.NOISE,
                GAScheduler.Lane.NOISE,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withTerrainWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    @WrapMethod(method = "generateStructureStarts")
    private static CompletableFuture<ChunkAccess> ga$parallelStructureStarts(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.STRUCTURE_STARTS,
                GAScheduler.Lane.WORKSPACE,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    @WrapMethod(method = "generateStructureReferences")
    private static CompletableFuture<ChunkAccess> ga$parallelStructureReferences(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.STRUCTURE_REFERENCES,
                GAScheduler.Lane.WORKSPACE,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    @WrapMethod(method = "generateSurface")
    private static CompletableFuture<ChunkAccess> ga$parallelSurface(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.SURFACE,
                GAScheduler.Lane.WORKSPACE,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    @WrapMethod(method = "generateCarvers")
    private static CompletableFuture<ChunkAccess> ga$parallelCarvers(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.CARVERS,
                GAScheduler.Lane.WORKSPACE,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    @WrapMethod(method = "generateSpawn")
    private static CompletableFuture<ChunkAccess> ga$parallelSpawn(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        return ga$drainMailboxAfter(GAChunkStatusPipeline.scheduleFuture(
                GAChunkStatusPipeline.Stage.SPAWN,
                GAScheduler.Lane.TRANSACTIONAL,
                step,
                chunk,
                () -> GAChunkWorkspaceRuntime.withImportedWorkspaceFuture(
                        chunk,
                        () -> original.call(context, step, cache, chunk)
                )
        ));
    }

    private static CompletableFuture<ChunkAccess> ga$drainMailboxAfter(CompletableFuture<ChunkAccess> future) {
        return GAChunkWorkspaceRuntime.drainCrossChunkMailboxAfter(future);
    }
}
