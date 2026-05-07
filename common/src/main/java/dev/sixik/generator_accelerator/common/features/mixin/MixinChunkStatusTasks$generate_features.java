package dev.sixik.generator_accelerator.common.features.mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks$generate_features {
    @Unique
    private static final Set<Heightmap.Types> GA$FEATURE_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
    );

    /**
     * @author Sixik
     * @reason Remove per-chunk EnumSet allocation in the generateFeatures hot path.
     */
    @Overwrite
    public static CompletableFuture<ChunkAccess> generateFeatures(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess) {
        ServerLevel serverLevel = worldGenContext.level();
        Heightmap.primeHeightmaps(chunkAccess, GA$FEATURE_HEIGHTMAPS);
        WorldGenRegion worldGenRegion = new WorldGenRegion(serverLevel, staticCache2D, chunkStep, chunkAccess);
        worldGenContext.generator().applyBiomeDecoration(worldGenRegion, chunkAccess, serverLevel.structureManager().forWorldGenRegion(worldGenRegion));
        Blender.generateBorderTicks(worldGenRegion, chunkAccess);
        return CompletableFuture.completedFuture(chunkAccess);
    }
}
