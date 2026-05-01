package dev.sixik.generator_accelerator.common.flat_block_structure.mixin;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks$inject_flat_block_structure {

    @Inject(method = "generateNoise", at = @At("HEAD"))
    private static void bts$generateNoise(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess pChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {

            final LevelChunkSection section = sections[i];
            if (section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$unpackForGeneration();
        }
    }

    @Inject(method = "generateSpawn", at = @At("HEAD"))
    private static void bts$generateSpawn(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess pChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {

            final LevelChunkSection section = sections[i];
            if (section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$packAndFreeze();
        }
    }

    @Inject(method = "full", at = @At("HEAD"))
    private static void bts$full(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess pChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {

            final LevelChunkSection section = sections[i];
            if (section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$packAndFreeze();
        }
    }
}
