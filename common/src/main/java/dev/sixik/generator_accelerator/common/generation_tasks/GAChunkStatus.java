package dev.sixik.generator_accelerator.common.generation_tasks;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.concurrent.CompletableFuture;

public class GAChunkStatus {

    public static ChunkStatus TERRAIN;

    public static CompletableFuture<ChunkAccess> generateTerrain(
            WorldGenContext pWorldGenContext, ChunkStep pStep, StaticCache2D<GenerationChunkHolder> pCache, ChunkAccess pChunk
    ) {
        LevelChunkSection[] sections = pChunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            final LevelChunkSection section = sections[i];
            if(section == null) continue;
            LevelChunkSection$FlatBlockArray.get(section).bts$unpackForGeneration();
        }

        ServerLevel serverlevel = pWorldGenContext.level();
        WorldGenRegion worldgenregion = new WorldGenRegion(serverlevel, pCache, pStep, pChunk);

        /*
            Noise
         */
        return pWorldGenContext.generator()
                .fillFromNoise(Blender.of(worldgenregion), serverlevel.getChunkSource().randomState(), serverlevel.structureManager().forWorldGenRegion(worldgenregion), pChunk)
                .thenApply(generatedChunk -> {
                    if (generatedChunk instanceof ProtoChunk protochunk) {
                        BelowZeroRetrogen belowzeroretrogen = protochunk.getBelowZeroRetrogen();
                        if (belowzeroretrogen != null) {
                            BelowZeroRetrogen.replaceOldBedrock(protochunk);
                            if (belowzeroretrogen.hasBedrockHoles()) {
                                belowzeroretrogen.applyBedrockMask(protochunk);
                            }
                        }
                    }

                    /*
                        Surface
                     */
                    pWorldGenContext.generator().buildSurface(worldgenregion, serverlevel.structureManager().forWorldGenRegion(worldgenregion), serverlevel.getChunkSource().randomState(), generatedChunk);

                    /*
                        Caves
                     */
                    if (generatedChunk instanceof ProtoChunk protochunk) {
                        Blender.addAroundOldChunksCarvingMaskFilter(worldgenregion, protochunk);
                    }


                    pWorldGenContext.generator().applyCarvers(
                            worldgenregion,
                            serverlevel.getSeed(),
                            serverlevel.getChunkSource().randomState(),
                            serverlevel.getBiomeManager(),
                            serverlevel.structureManager().forWorldGenRegion(worldgenregion),
                            pChunk,
                            GenerationStep.Carving.AIR
                    );

                    for (int i = 0; i < sections.length; i++) {
                        final LevelChunkSection section = sections[i];
                        if(section == null) continue;
                        LevelChunkSection$FlatBlockArray.get(section).bts$packAndFreeze();
                    }

                    return generatedChunk;
                });
    }
}
