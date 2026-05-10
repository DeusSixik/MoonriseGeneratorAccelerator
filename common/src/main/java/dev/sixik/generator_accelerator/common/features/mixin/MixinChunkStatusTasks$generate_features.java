package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.common.features.ChunkAccess$primeFeatureHeightmapsUnsynchronized;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspacePool;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks$generate_features {
    /**
     * @author Sixik
     * @reason Skip redundant feature heightmap priming while keeping the hot path allocation-free.
     */
    @Overwrite
    public static CompletableFuture<ChunkAccess> generateFeatures(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess) {
        GAChunkWorkspace workspace = GAChunkWorkspacePool.acquire(chunkAccess, false);
        try (GAChunkWorkspaceContext.Scope ignored = GAChunkWorkspaceContext.bind(workspace)) {
            ServerLevel serverLevel = worldGenContext.level();
            ((ChunkAccess$primeFeatureHeightmapsUnsynchronized) chunkAccess).ga$primeFeatureHeightmapsIfMissing();
            WorldGenRegion worldGenRegion = new WorldGenRegion(serverLevel, staticCache2D, chunkStep, chunkAccess);
            worldGenContext.generator().applyBiomeDecoration(worldGenRegion, chunkAccess, serverLevel.structureManager().forWorldGenRegion(worldGenRegion));
            Blender.generateBorderTicks(worldGenRegion, chunkAccess);
            return CompletableFuture.completedFuture(chunkAccess);
        } finally {
            GAChunkWorkspacePool.release(workspace);
        }
    }
}
