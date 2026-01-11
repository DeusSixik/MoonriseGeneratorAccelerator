package dev.sixik.moonrisegeneratoraccelerator.profiler.mixin.vanilla.generation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.moonrisegeneratoraccelerator.profiler.BtsProfilerUtils;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks$add_profiler {

    @Unique
    private static final String PassThroughZone = "Pass Through";
    private static final String GenerateStructureStartsZone = "Generate Structure Starts";
    private static final String LoadStructureStartsZone = "Load Structure Starts";
    private static final String GenerateStructureReferencesZone = "Generate Structure References";
    private static final String GenerateBiomesZone = "Generate Biomes";
    private static final String GenerateNoiseZone = "Generate Noise";
    private static final String GenerateSurfaceZone = "Generate Surface";
    private static final String GenerateCarversZone = "Generate Carvers";
    private static final String GenerateFeaturesZone = "Generate Features";
    private static final String InitializeLightZone = "Initialize Light";
    private static final String LightZone = "Light";
    private static final String GenerateSpawnZone = "GenerateSpawn";
    private static final String FullZone = "Full";

    @WrapMethod(method = "passThrough")
    private static CompletableFuture<ChunkAccess> bts$passThrough(
            WorldGenContext worldGenContext,
            ChunkStep chunkStep,
            StaticCache2D<GenerationChunkHolder> staticCache2D,
            ChunkAccess chunkAccess,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {

        BtsProfilerUtils.startZone(PassThroughZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(PassThroughZone);
        }
    }

    @WrapMethod(method = "generateStructureStarts")
    private static CompletableFuture<ChunkAccess> bts$generateStructureStarts(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateStructureStartsZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateStructureStartsZone);
        }
    }

    @WrapMethod(method = "loadStructureStarts")
    private static CompletableFuture<ChunkAccess> bts$loadStructureStarts(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(LoadStructureStartsZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(LoadStructureStartsZone);
        }
    }

    @WrapMethod(method = "generateStructureReferences")
    private static CompletableFuture<ChunkAccess> bts$generateStructureReferences(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateStructureReferencesZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateStructureReferencesZone);
        }
    }

    @WrapMethod(method = "generateBiomes")
    private static CompletableFuture<ChunkAccess> bts$generateBiomes(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateBiomesZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateBiomesZone);
        }
    }

    @WrapMethod(method = "generateNoise")
    private static CompletableFuture<ChunkAccess> bts$generateNoise(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateNoiseZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateNoiseZone);
        }
    }

    @WrapMethod(method = "generateSurface")
    private static CompletableFuture<ChunkAccess> bts$generateSurface(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateSurfaceZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateSurfaceZone);
        }
    }

    @WrapMethod(method = "generateCarvers")
    private static CompletableFuture<ChunkAccess> bts$generateCarvers(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateCarversZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateCarversZone);
        }
    }

    @WrapMethod(method = "generateFeatures")
    private static CompletableFuture<ChunkAccess> bts$generateFeatures(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateFeaturesZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateFeaturesZone);
        }
    }

    @WrapMethod(method = "initializeLight")
    private static CompletableFuture<ChunkAccess> bts$initializeLight(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(InitializeLightZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(InitializeLightZone);
        }
    }

    @WrapMethod(method = "light")
    private static CompletableFuture<ChunkAccess> bts$light(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(LightZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(LightZone);
        }
    }

    @WrapMethod(method = "generateSpawn")
    private static CompletableFuture<ChunkAccess> bts$generateSpawn(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(GenerateSpawnZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(GenerateSpawnZone);
        }
    }

    @WrapMethod(method = "full")
    private static CompletableFuture<ChunkAccess> bts$full(WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, Operation<CompletableFuture<ChunkAccess>> original) {
        BtsProfilerUtils.startZone(FullZone);
        try {
            return original.call(worldGenContext, chunkStep, staticCache2D, chunkAccess);
        } finally {
            BtsProfilerUtils.endZone(FullZone);
        }
    }
}
