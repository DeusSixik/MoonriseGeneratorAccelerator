package dev.sixik.generator_accelerator.common.modernfix.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor {
    @Accessor("futures")
    AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> ga$getFutures();

    @Accessor("startedWork")
    AtomicReference<ChunkStatus> ga$getStartedWork();
}
