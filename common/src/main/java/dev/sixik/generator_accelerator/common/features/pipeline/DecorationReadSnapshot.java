package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.biome.region.GARegionalBiomeSectionRaster;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Detached block-id view for parallel decoration kernels.
 *
 * <p>The capture phase runs before worker tasks start. Workers only read copied
 * int arrays, so native kernels do not touch PalettedContainer/BulkSectionAccess
 * from multiple threads.</p>
 */
final class DecorationReadSnapshot {
    private static final Object LIVE_SECTION_COPY_LOCK = new Object();
    private static final int CHUNK_WIDTH = GAChunkWorkspace.CHUNK_WIDTH;
    private static final int AIR_ID = Block.getId(Blocks.AIR.defaultBlockState());

    private final Entry[] entries;
    private final int minBuildHeight;
    private final int buildHeight;
    private final int minQuartY;
    private final int quartHeight;

    private DecorationReadSnapshot(Entry[] entries, int minBuildHeight, int buildHeight) {
        this.entries = entries;
        this.minBuildHeight = minBuildHeight;
        this.buildHeight = buildHeight;
        this.minQuartY = QuartPos.fromBlock(minBuildHeight);
        this.quartHeight = Math.max(1, QuartPos.fromBlock(buildHeight));
    }

    static DecorationReadSnapshot capture(DecorationPipelineExecutor.ExecutionContext context, int radius) {
        int safeRadius = Math.max(0, radius);
        int side = safeRadius * 2 + 1;
        Entry[] entries = new Entry[side * side];
        int index = 0;
        int centerX = context.chunkX();
        int centerZ = context.chunkZ();
        int minBuildHeight = context.chunk().getMinBuildHeight();
        int buildHeight = context.chunk().getHeight();
        GARegionalBiomeSectionRaster.View biomeRaster = null;
        if (GARegionalBiomeSectionRaster.enabled()) {
            int regionChunkX = centerX >> GARegionalBiomeSectionRaster.REGION_CHUNK_SHIFT;
            int regionChunkZ = centerZ >> GARegionalBiomeSectionRaster.REGION_CHUNK_SHIFT;
            int regionMinChunkX = regionChunkX << GARegionalBiomeSectionRaster.REGION_CHUNK_SHIFT;
            int regionMinChunkZ = regionChunkZ << GARegionalBiomeSectionRaster.REGION_CHUNK_SHIFT;
            ChunkAccess[] regionChunks = new ChunkAccess[GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE
                    * GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE];
            for (int localChunkZ = 0; localChunkZ < GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE; localChunkZ++) {
                for (int localChunkX = 0; localChunkX < GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE; localChunkX++) {
                    int chunkX = regionMinChunkX + localChunkX;
                    int chunkZ = regionMinChunkZ + localChunkZ;
                    regionChunks[localChunkX | (localChunkZ << GARegionalBiomeSectionRaster.REGION_CHUNK_SHIFT)] =
                            chunkX == centerX && chunkZ == centerZ
                                    ? context.chunk()
                                    : context.level().getChunk(chunkX, chunkZ);
                }
            }
            biomeRaster = GARegionalBiomeSectionRaster.capture(
                    regionMinChunkX,
                    regionMinChunkZ,
                    regionChunks,
                    minBuildHeight,
                    buildHeight
            );
        }
        for (int dz = -safeRadius; dz <= safeRadius; dz++) {
            for (int dx = -safeRadius; dx <= safeRadius; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                ChunkAccess chunk = chunkX == centerX && chunkZ == centerZ
                        ? context.chunk()
                        : context.level().getChunk(chunkX, chunkZ);
                entries[index++] = copyChunk(
                        context.workspace(),
                        chunk,
                        centerX,
                        centerZ,
                        minBuildHeight,
                        buildHeight,
                        biomeRaster
                );
            }
        }
        return new DecorationReadSnapshot(entries, minBuildHeight, buildHeight);
    }

    Integer blockIdAt(int x, int y, int z) {
        Entry entry = entryAt(x >> 4, z >> 4);
        if (entry == null || !containsY(y)) {
            return null;
        }
        int localY = y - minBuildHeight;
        return entry.blockIds[(localY << 8) | ((z & 15) << 4) | (x & 15)];
    }

    boolean containsChunk(int chunkX, int chunkZ) {
        return entryAt(chunkX, chunkZ) != null;
    }

    boolean containsY(int y) {
        return y >= minBuildHeight && y < minBuildHeight + buildHeight;
    }

    Holder<Biome> biomeAt(int x, int y, int z) {
        int quartX = QuartPos.fromBlock(x);
        int quartZ = QuartPos.fromBlock(z);
        Entry entry = entryAt(QuartPos.toSection(quartX), QuartPos.toSection(quartZ));
        if (entry == null) {
            return null;
        }
        int quartY = Mth.clamp(QuartPos.fromBlock(y), minQuartY, minQuartY + quartHeight - 1);
        int localQuartY = quartY - minQuartY;
        return entry.biomes[(localQuartY << 4) | ((quartZ & 3) << 2) | (quartX & 3)];
    }

    private Entry entryAt(int chunkX, int chunkZ) {
        for (Entry entry : entries) {
            if (entry.chunkX == chunkX && entry.chunkZ == chunkZ) {
                return entry;
            }
        }
        return null;
    }

    private static Entry copyChunk(
            GAChunkWorkspace workspace,
            ChunkAccess chunk,
            int centerX,
            int centerZ,
            int minBuildHeight,
            int buildHeight,
            GARegionalBiomeSectionRaster.View biomeRaster
    ) {
        int[] blockIds;
        if (workspace != null
                && workspace.blockBufferEnabled()
                && chunk.getPos().x == centerX
                && chunk.getPos().z == centerZ
                && workspace.minBuildHeight() == minBuildHeight
                && workspace.buildHeight() == buildHeight) {
            blockIds = Arrays.copyOf(workspace.blockIds(), workspace.blockCount());
        } else {
            blockIds = new int[Math.max(0, buildHeight) * GAChunkWorkspace.COLUMN_COUNT];
            synchronized (LIVE_SECTION_COPY_LOCK) {
                copyLiveChunk(chunk, blockIds, minBuildHeight, buildHeight);
            }
        }

        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[Math.max(1, QuartPos.fromBlock(buildHeight)) * 16];
        if (biomeRaster != null
                && chunk.getPos().x >= biomeRaster.regionChunkX()
                && chunk.getPos().x < biomeRaster.regionChunkX() + GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE
                && chunk.getPos().z >= biomeRaster.regionChunkZ()
                && chunk.getPos().z < biomeRaster.regionChunkZ() + GARegionalBiomeSectionRaster.REGION_CHUNK_SIZE) {
            biomeRaster.copyChunkBiomes(chunk.getPos().x, chunk.getPos().z, biomes);
        } else {
            synchronized (LIVE_SECTION_COPY_LOCK) {
                copyLiveBiomes(chunk, biomes, minBuildHeight, buildHeight);
            }
        }
        return new Entry(chunk.getPos().x, chunk.getPos().z, blockIds, biomes);
    }

    private static void copyLiveChunk(ChunkAccess chunk, int[] blockIds, int minBuildHeight, int buildHeight) {
        LevelChunkSection[] sections = chunk.getSections();
        int maxY = minBuildHeight + buildHeight;
        for (int y = minBuildHeight; y < maxY; y++) {
            int sectionIndex = Math.floorDiv(y, CHUNK_WIDTH) - chunk.getMinSection();
            LevelChunkSection section = sectionIndex < 0 || sectionIndex >= sections.length ? null : sections[sectionIndex];
            boolean airSection = section == null || section.hasOnlyAir();
            int[] raw = airSection ? null : LevelChunkSection$FlatBlockArray.rawData(section);
            int localY = y & 15;
            int baseIndex = (y - minBuildHeight) << 8;
            if (airSection) {
                Arrays.fill(blockIds, baseIndex, baseIndex + GAChunkWorkspace.COLUMN_COUNT, AIR_ID);
                continue;
            }
            if (raw != null) {
                for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                    int src = (localY << 8) | (localZ << 4);
                    int dst = baseIndex | (localZ << 4);
                    System.arraycopy(raw, src, blockIds, dst, CHUNK_WIDTH);
                }
                continue;
            }
            for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                int rowIndex = baseIndex | (localZ << 4);
                for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                    blockIds[rowIndex | localX] = Block.getId(section.getBlockState(localX, localY, localZ));
                }
            }
        }
    }

    private static void copyLiveBiomes(ChunkAccess chunk, Holder<Biome>[] biomes, int minBuildHeight, int buildHeight) {
        LevelChunkSection[] sections = chunk.getSections();
        int minQuartY = QuartPos.fromBlock(minBuildHeight);
        int quartHeight = Math.max(1, QuartPos.fromBlock(buildHeight));
        for (int localQuartY = 0; localQuartY < quartHeight; localQuartY++) {
            int quartY = minQuartY + localQuartY;
            int sectionIndex = Math.floorDiv(QuartPos.toBlock(quartY), CHUNK_WIDTH) - chunk.getMinSection();
            LevelChunkSection section = sectionIndex < 0 || sectionIndex >= sections.length ? null : sections[sectionIndex];
            if (section == null) {
                continue;
            }
            int dstBase = localQuartY << 4;
            int localY = quartY & 3;
            for (int localQuartZ = 0; localQuartZ < 4; localQuartZ++) {
                for (int localQuartX = 0; localQuartX < 4; localQuartX++) {
                    biomes[dstBase | (localQuartZ << 2) | localQuartX] =
                            section.getNoiseBiome(localQuartX, localY, localQuartZ);
                }
            }
        }
    }

    private record Entry(int chunkX, int chunkZ, int[] blockIds, Holder<Biome>[] biomes) {
    }
}
