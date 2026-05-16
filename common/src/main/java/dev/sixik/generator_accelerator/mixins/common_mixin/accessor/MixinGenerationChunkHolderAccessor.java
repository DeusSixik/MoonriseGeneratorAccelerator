package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

@Mixin(GenerationChunkHolder.class)
public interface MixinGenerationChunkHolderAccessor {
    @Invoker("applyStep")
    CompletableFuture<ChunkResult<ChunkAccess>> ga$applyStep(
            ChunkStep step,
            GeneratingChunkMap chunkMap,
            StaticCache2D<GenerationChunkHolder> cache
    );
}
