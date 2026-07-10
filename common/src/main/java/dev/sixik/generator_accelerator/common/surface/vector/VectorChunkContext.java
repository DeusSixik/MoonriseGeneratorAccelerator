package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;

import java.util.Arrays;
import java.util.BitSet;

public class VectorChunkContext {

    public Holder<Biome>[] surfaceBiomes;

    public final short[] surfaceHeights = new short[256];
    public final int[] surfaceDepths = new int[256];
    public final double[] secondarySurfaceNoises = new double[256];
    public final double[] noiseColumnCache = new double[256];
    public final int[] columnScratchMarks = new int[256];
    public final int[] weightedRuleByColumn = new int[256];
    public final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private BitSet[] bitSetScratch = new BitSet[8];
    private int bitSetScratchDepth;
    private int columnScratchStamp;

    public int STONE_ID;
    public int AIR_ID;
    public int WATER_ID;

    public final byte[] stoneDepthAbove = new byte[4096];
    public final byte[] stoneDepthBelow = new byte[4096];
    public final int[] minSurfaceLevels = new int[256];
    public final int[] waterHeights = new int[256];

    public int sectionStartX;
    public int sectionStartY;
    public int sectionStartZ;
    public WorldGenerationContext worldContext;
    public RandomState randomState;
    public SurfaceSystem surfaceSystem;

    public VectorChunkContext(Holder<Biome>[] surfaceBiomes, int defaultBlockId, WorldGenerationContext worldContext, RandomState randomState, SurfaceSystem surfaceSystem) {
        reset(surfaceBiomes, defaultBlockId, worldContext, randomState, surfaceSystem);
    }

    public void reset(Holder<Biome>[] surfaceBiomes, int defaultBlockId, WorldGenerationContext worldContext, RandomState randomState, SurfaceSystem surfaceSystem) {
        this.surfaceBiomes = surfaceBiomes;
        this.STONE_ID = defaultBlockId;
        this.AIR_ID = GA$BlockStateExtension.get(Blocks.AIR.defaultBlockState()).bts$getFastId();
        this.WATER_ID = GA$BlockStateExtension.get(Blocks.WATER.defaultBlockState()).bts$getFastId();
        this.worldContext = worldContext;
        this.randomState = randomState;
        this.surfaceSystem = surfaceSystem;
    }

    public void clear() {
        this.surfaceBiomes = null;
        this.worldContext = null;
        this.randomState = null;
        this.surfaceSystem = null;
        this.bitSetScratchDepth = 0;
    }

    public void updateForSection(int startX, int startY, int startZ) {
        this.sectionStartX = startX;
        this.sectionStartY = startY;
        this.sectionStartZ = startZ;
    }

    public Holder<Biome> getBiome(int xzIdx) {
        return this.surfaceBiomes[xzIdx & 255];
    }

    public BitSet acquireBitSet4096() {
        int index = this.bitSetScratchDepth;
        if (index >= this.bitSetScratch.length) {
            this.bitSetScratch = Arrays.copyOf(this.bitSetScratch, this.bitSetScratch.length << 1);
        }
        BitSet mask = this.bitSetScratch[index];
        if (mask == null) {
            mask = new BitSet(4096);
            this.bitSetScratch[index] = mask;
        } else {
            mask.clear();
        }
        this.bitSetScratchDepth = index + 1;
        return mask;
    }

    public void releaseBitSet4096(BitSet mask) {
        if (this.bitSetScratchDepth <= 0) {
            mask.clear();
            return;
        }
        mask.clear();
        this.bitSetScratchDepth--;
    }

    public int nextColumnScratchStamp() {
        int next = this.columnScratchStamp + 1;
        if (next == 0) {
            Arrays.fill(this.columnScratchMarks, 0);
            next = 1;
        }
        this.columnScratchStamp = next;
        return next;
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
        prepareSurfaceDepthCache(surfaceSystem, chunkMinX, chunkMinZ);
        prepareSecondarySurfaceNoiseCache(surfaceSystem, chunkMinX, chunkMinZ);
    }

    public void prepareSurfaceDepthCache(SurfaceSystem surfaceSystem, int chunkMinX, int chunkMinZ) {
        int[] depths = this.surfaceDepths;
        for (int xz = 0; xz < 256; xz++) {
            depths[xz] = surfaceSystem.getSurfaceDepth(chunkMinX + (xz & 15), chunkMinZ + (xz >> 4));
        }
    }

    public void prepareSecondarySurfaceNoiseCache(SurfaceSystem surfaceSystem, int chunkMinX, int chunkMinZ) {
        double[] noises = this.secondarySurfaceNoises;
        for (int xz = 0; xz < 256; xz++) {
            noises[xz] = surfaceSystem.getSurfaceSecondary(chunkMinX + (xz & 15), chunkMinZ + (xz >> 4));
        }
    }

    public void calculateStoneDepths(int[] rawBlockData, int[] previousSectionBottomDepths) {
        calculateStoneDepths(rawBlockData, previousSectionBottomDepths, null);
    }

    public void calculateStoneDepthsAndLoadStoneMask(int[] rawBlockData, int[] previousSectionBottomDepths, Mask4096 stoneMask) {
        calculateStoneDepths(rawBlockData, previousSectionBottomDepths, stoneMask);
    }

    private void calculateStoneDepths(int[] rawBlockData, int[] previousSectionBottomDepths, Mask4096 stoneMask) {
        long[] stoneWords = stoneMask == null ? null : stoneMask.words();
        if (stoneWords != null) {
            Arrays.fill(stoneWords, 0L);
        }

        int stoneId = this.STONE_ID;
        int airId = this.AIR_ID;
        int waterId = this.WATER_ID;
        int sectionStartY = this.sectionStartY;
        byte[] depthAbove = this.stoneDepthAbove;
        byte[] depthBelow = this.stoneDepthBelow;
        int[] waterHeights = this.waterHeights;

        for (int xzIdx = 0; xzIdx < 256; xzIdx++) {
            int depthCounterAbove = previousSectionBottomDepths[xzIdx];
            int waterHeight = waterHeights[xzIdx];
            for (int y = 15; y >= 0; y--) {
                int index = (y << 8) | xzIdx;
                int blockId = rawBlockData[index];

                boolean isStone = blockId == stoneId;
                boolean isSolid = isStone || (blockId != airId && blockId != waterId);
                if (stoneWords != null && isStone) {
                    stoneWords[index >>> 6] |= 1L << (index & 63);
                }

                if (isSolid) {
                    depthCounterAbove++;
                    depthAbove[index] = (byte) Math.min(depthCounterAbove, 127);
                } else {
                    depthCounterAbove = 0;
                    depthAbove[index] = 0;
                }

                if (waterHeight == Integer.MIN_VALUE && blockId == waterId) {
                    waterHeight = sectionStartY + y + 1;
                }
            }
            previousSectionBottomDepths[xzIdx] = depthCounterAbove;
            waterHeights[xzIdx] = waterHeight;

            int depthCounterBelow = 0;
            for (int y = 0; y < 16; y++) {
                int index = (y << 8) | xzIdx;
                int blockId = rawBlockData[index];
                boolean isSolid = blockId == stoneId || (blockId != airId && blockId != waterId);

                if (isSolid) {
                    depthCounterBelow++;
                    depthBelow[index] = (byte) Math.min(depthCounterBelow, 127);
                } else {
                    depthCounterBelow = 0;
                    depthBelow[index] = 0;
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

