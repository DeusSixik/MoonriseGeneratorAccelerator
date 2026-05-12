package dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone;

import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.common.features.compat.biomeswevegone.GABWGExtensions;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ChunkStatusTasks.class, priority = 1600)
public abstract class BiomesWeveGone$ChunkStatusTasksMixin$cache_biomes {

    @Inject(
            method = "generateSurface(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"
            )
    )
    private static void ga$runBWGExtensionsWithFlatCache(
            WorldGenContext worldGenContext,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir,
            @Local ServerLevel serverLevel,
            @Local WorldGenRegion worldGenRegion
    ) {
        GABWGExtensions.runExtensions(worldGenContext, chunk, serverLevel, worldGenRegion);
    }
}
