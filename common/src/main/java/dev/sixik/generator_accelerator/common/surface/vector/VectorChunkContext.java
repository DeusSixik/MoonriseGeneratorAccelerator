package dev.sixik.generator_accelerator.common.surface.vector;

import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;

public class VectorChunkContext {

    public final Holder<Biome>[] surfaceBiomes;

    public final short[] surfaceHeights = new short[256];
    public final int[] surfaceDepths = new int[256];
    public final double[] secondarySurfaceNoises = new double[256];

    public final int STONE_ID;
    public final int AIR_ID;
    public final int WATER_ID;

    public final byte[] stoneDepthAbove = new byte[4096];
    public final byte[] stoneDepthBelow = new byte[4096];
    public final int[] minSurfaceLevels = new int[256];
    public final int[] waterHeights = new int[256];

    public int sectionStartX;
    public int sectionStartY;
    public int sectionStartZ;
    public final WorldGenerationContext worldContext;
    public final RandomState randomState;
    public final SurfaceSystem surfaceSystem;

    public VectorChunkContext(Holder<Biome>[] surfaceBiomes, int defaultBlockId, WorldGenerationContext worldContext, RandomState randomState, SurfaceSystem surfaceSystem) {
        this.surfaceBiomes = surfaceBiomes;
        this.STONE_ID = defaultBlockId;
        this.AIR_ID = Block.getId(Blocks.AIR.defaultBlockState());
        this.WATER_ID = Block.getId(Blocks.WATER.defaultBlockState());

        this.worldContext = worldContext;
        this.randomState = randomState;
        this.surfaceSystem = surfaceSystem;
    }

    public void updateForSection(int startX, int startY, int startZ) {
        this.sectionStartX = startX;
        this.sectionStartY = startY;
        this.sectionStartZ = startZ;
//        Arrays.fill(this.waterHeights, Integer.MIN_VALUE);
    }

    public Holder<Biome> getBiome(int xzIdx) {
        return this.surfaceBiomes[xzIdx & 255];
    }

    public void buildDepthMap(ChunkAccess chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int idx = x | (z << 4);
                this.surfaceHeights[idx] = (short) (chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
                this.waterHeights[idx] = Integer.MIN_VALUE;
            }
        }
    }

    public void prepareNoiseCaches(SurfaceSystem surfaceSystem, int chunkMinX, int chunkMinZ) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int globalX = chunkMinX + x;
                int globalZ = chunkMinZ + z;
                int index = x | (z << 4);

                this.surfaceDepths[index] = surfaceSystem.getSurfaceDepth(globalX, globalZ);
                this.secondarySurfaceNoises[index] = surfaceSystem.getSurfaceSecondary(globalX, globalZ);
            }
        }
    }

    public void calculateStoneDepths(int[] rawBlockData, int[] previousSectionBottomDepths) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int xzIdx = x | (z << 4);

                int depthCounterAbove = previousSectionBottomDepths[xzIdx];
                for (int y = 15; y >= 0; y--) {
                    int index = (y << 8) | (z << 4) | x;
                    int blockId = rawBlockData[index];

                    boolean isSolid = blockId == STONE_ID || (blockId != AIR_ID && blockId != WATER_ID);

                    if (isSolid) {
                        depthCounterAbove++;
                        this.stoneDepthAbove[index] = (byte) Math.min(depthCounterAbove, 127); // Защита от переполнения byte
                    } else {
                        depthCounterAbove = 0;
                        this.stoneDepthAbove[index] = 0;
                    }

                    if (this.waterHeights[xzIdx] == Integer.MIN_VALUE && blockId == WATER_ID) {
                        this.waterHeights[xzIdx] = this.sectionStartY + y + 1;
                    }
                }
                previousSectionBottomDepths[xzIdx] = depthCounterAbove;

                int depthCounterBelow = 0;
                for (int y = 0; y <= 15; y++) {
                    int index = (y << 8) | (z << 4) | x;
                    int blockId = rawBlockData[index];

                    boolean isSolid = blockId == STONE_ID || (blockId != AIR_ID && blockId != WATER_ID);

                    if (isSolid) {
                        depthCounterBelow++;
                        this.stoneDepthBelow[index] = (byte) Math.min(depthCounterBelow, 127);
                    } else {
                        depthCounterBelow = 0;
                        this.stoneDepthBelow[index] = 0;
                    }
                }
            }
        }
    }

    public void preparePreliminarySurface(NoiseChunk noiseChunk, int minX, int minZ) {
        int cellX = minX >> 4;
        int cellZ = minZ >> 4;

        int c00 = noiseChunk.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
        int c10 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
        int c01 = noiseChunk.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
        int c11 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);

        for (int x = 0; x < 16; x++) {
            float lerpX = x / 16.0f;
            for (int z = 0; z < 16; z++) {
                float lerpZ = z / 16.0f;
                int xzIdx = x | (z << 4);

                int lerpedHeight = Mth.floor(Mth.lerp2(lerpX, lerpZ, c00, c10, c01, c11));
                this.minSurfaceLevels[xzIdx] = lerpedHeight + this.surfaceDepths[xzIdx] - 8;
            }
        }
    }
}
