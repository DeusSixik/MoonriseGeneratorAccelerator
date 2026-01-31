package dev.sixik.density_interpreter.tests;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public class NoiseChunkGenerationPipeline {

    public static void startGeneration(
            NoiseChunk noiseChunk,
            Blender blender, StructureManager structureManager,
            RandomState randomState, ChunkAccess chunk,
            int minCellY, int cellCountY
    ) {

    }
}
