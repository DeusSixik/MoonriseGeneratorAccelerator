package dev.sixik.generator_accelerator.common.surface.vector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class VectorChunkContext {

    public final Function<BlockPos, Holder<Biome>> biomeManager;
    private final Map<ResourceKey<Biome>, BitSet> biomeMaskCache = new HashMap<>();
    public final short[] surfaceHeights = new short[256];
    public final int[] surfaceDepths = new int[256];
    public final double[] secondarySurfaceNoises = new double[256];

    public final int STONE_ID;

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

    public VectorChunkContext(Function<BlockPos, Holder<Biome>> biomeManager, int defaultBlockId, WorldGenerationContext worldContext, RandomState randomState, SurfaceSystem surfaceSystem) {
        this.biomeManager = biomeManager;
        this.STONE_ID = defaultBlockId;
        this.worldContext = worldContext;
        this.randomState = randomState;
        this.surfaceSystem = surfaceSystem;
        Arrays.fill(this.waterHeights, Integer.MIN_VALUE);
    }

    public void updateForSection(int startX, int startY, int startZ) {
        this.sectionStartX = startX;
        this.sectionStartY = startY;
        this.sectionStartZ = startZ;
        this.biomeMaskCache.clear();
    }

    public BitSet getBiomeMask(ResourceKey<Biome> targetBiome) {
        final BitSet value_from_cache = biomeMaskCache.get(targetBiome);
        if(value_from_cache != null) return value_from_cache;

        BitSet mask = new BitSet(4096);
        final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    int worldX = this.sectionStartX + (bx * 4);
                    int worldY = this.sectionStartY + (by * 4);
                    int worldZ = this.sectionStartZ + (bz * 4);

                    Holder<Biome> holder = biomeManager.apply(blockPos.set(worldX, worldY, worldZ));

                    if(holder.is(targetBiome)) {
                        fillBiomeCubeInMask(mask, bx, by, bz);
                    }
                }
            }
        }
        biomeMaskCache.put(targetBiome, mask);
        return mask;
    }

    private void fillBiomeCubeInMask(BitSet mask, int bx, int by, int bz) {
        int startX = bx * 4;
        int startY = by * 4;
        int startZ = bz * 4;

        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int index = ((startY + y) << 8) | ((startZ + z) << 4) | (startX + x);
                    mask.set(index);
                }
            }
        }
    }

    public void buildDepthMap(ChunkAccess chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {

                // +1 because vanilla thinks the same
                this.surfaceHeights[x | (z << 4)] = (short) (chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
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

    public void calculateStoneDepths(int[] rawBlockData, int previousSectionBottomDepths[]) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int xzIdx = x | (z << 4);
                int depthCounter = previousSectionBottomDepths[x | (z << 4)];

                for (int y = 15; y >= 0; y--) {
                    int index = (y << 8) | (z << 4) | x;
                    int blockId = rawBlockData[index];

                    BlockState currentBlock = Block.stateById(blockId);

                    if (!currentBlock.isAir() && currentBlock.getFluidState().isEmpty()) {
                        depthCounter++;
                        this.stoneDepthAbove[index] = (byte) depthCounter;
                    } else {
                        depthCounter = 0;
                        this.stoneDepthAbove[index] = 0;
                    }

                    if (this.waterHeights[xzIdx] == Integer.MIN_VALUE) {
                        if (isLiquid(currentBlock)) {
                            this.waterHeights[xzIdx] = this.sectionStartY + y;
                        }
                    }
                }

                previousSectionBottomDepths[x | (z << 4)] = depthCounter;
            }
        }
    }

    private boolean isLiquid(BlockState currentBlock) {
        return !currentBlock.getFluidState().isEmpty();
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
