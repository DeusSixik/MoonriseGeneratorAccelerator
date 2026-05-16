package dev.sixik.generator_accelerator.common.worldgen.workspace;

import java.util.Arrays;

/**
 * Generic terrain-stage helpers over workspace-owned buffers.
 *
 * <p>These passes intentionally know only flat block ids and chunk-local
 * coordinates, so future mixins can call them without binding this prototype to
 * Minecraft noise, aquifer, surface, or carver internals.</p>
 */
public final class GATerrainWorkspacePasses {
    private GATerrainWorkspacePasses() {
    }

    public static long fillDensity(GAChunkWorkspace workspace, DensitySampler sampler) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (sampler == null) {
            throw new NullPointerException("sampler");
        }

        long start = System.nanoTime();
        long samples = 0L;
        int blockCount = workspace.blockCount();
        workspace.ensureDensityBufferCapacity(blockCount);
        double[] densities = requireDensityBuffer(workspace, blockCount);
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    densities[index++] = sampler.density(localX, y, localZ);
                    samples++;
                }
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.markDensityReady();
        return samples;
    }

    public static long fillAquifer(GAChunkWorkspace workspace, AquiferSampler sampler) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (sampler == null) {
            throw new NullPointerException("sampler");
        }

        long start = System.nanoTime();
        long decisions = 0L;
        int blockCount = workspace.blockCount();
        workspace.ensureAquiferBufferCapacity(blockCount);
        int[] aquifers = requireAquiferBuffer(workspace, blockCount);
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    aquifers[index++] = sampler.blockId(localX, y, localZ);
                    decisions++;
                }
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.markAquiferReady();
        return decisions;
    }

    public static long materializeTerrainBlocks(GAChunkWorkspace workspace, TerrainBlockResolver resolver) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (resolver == null) {
            throw new NullPointerException("resolver");
        }

        long start = System.nanoTime();
        long changedBlocks = 0L;
        int blockCount = workspace.blockCount();
        boolean hadBlockBuffer = workspace.blockBufferEnabled();
        workspace.ensureBlockBufferCapacity(blockCount);
        int[] blocks = requireBlockBuffer(workspace, blockCount);
        if (!hadBlockBuffer) {
            Arrays.fill(blocks, 0, blockCount, GAChunkWorkspace.EMPTY_BLOCK_ID);
        }
        double[] densities = requireDensityBuffer(workspace, blockCount);
        int[] aquifers = requireAquiferBuffer(workspace, blockCount);
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    double density = densities[index];
                    int aquiferBlockId = aquifers[index];
                    int currentBlockId = blocks[index];
                    int resolvedBlockId = resolver.blockId(localX, y, localZ, density, aquiferBlockId, currentBlockId);
                    if (currentBlockId != resolvedBlockId) {
                        blocks[index] = resolvedBlockId;
                        markDirtyBlock(workspace, index);
                        changedBlocks++;
                    }
                    index++;
                }
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().addTerrainBlockWrites(changedBlocks);
        return changedBlocks;
    }

    public static TerrainPreparationResult fillDensityAndAquifer(
            GAChunkWorkspace workspace,
            DensitySampler densitySampler,
            AquiferSampler aquiferSampler
    ) {
        return fillDensityAquiferAndMaterialize(workspace, densitySampler, aquiferSampler, null);
    }

    public static TerrainPreparationResult fillDensityAquiferAndMaterialize(
            GAChunkWorkspace workspace,
            DensitySampler densitySampler,
            AquiferSampler aquiferSampler,
            TerrainBlockResolver resolver
    ) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (densitySampler == null) {
            throw new NullPointerException("densitySampler");
        }
        if (aquiferSampler == null) {
            throw new NullPointerException("aquiferSampler");
        }

        long start = System.nanoTime();
        int blockCount = workspace.blockCount();
        workspace.ensureDensityBufferCapacity(blockCount);
        workspace.ensureAquiferBufferCapacity(blockCount);
        double[] densities = requireDensityBuffer(workspace, blockCount);
        int[] aquifers = requireAquiferBuffer(workspace, blockCount);
        int[] blocks = null;
        boolean resolveTerrain = resolver != null;
        if (resolveTerrain) {
            boolean hadBlockBuffer = workspace.blockBufferEnabled();
            workspace.ensureBlockBufferCapacity(blockCount);
            blocks = requireBlockBuffer(workspace, blockCount);
            if (!hadBlockBuffer) {
                Arrays.fill(blocks, 0, blockCount, GAChunkWorkspace.EMPTY_BLOCK_ID);
            }
        }

        long samples = 0L;
        long decisions = 0L;
        long changedBlocks = 0L;
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    double density = densitySampler.density(localX, y, localZ);
                    int aquiferBlockId = aquiferSampler.blockId(localX, y, localZ);
                    densities[index] = density;
                    aquifers[index] = aquiferBlockId;
                    samples++;
                    decisions++;
                    if (resolveTerrain) {
                        int currentBlockId = blocks[index];
                        int resolvedBlockId = resolver.blockId(localX, y, localZ, density, aquiferBlockId, currentBlockId);
                        if (currentBlockId != resolvedBlockId) {
                            blocks[index] = resolvedBlockId;
                            markDirtyBlock(workspace, index);
                            changedBlocks++;
                        }
                    }
                    index++;
                }
            }
        }

        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().incrementTerrainPasses();
        if (resolveTerrain) {
            workspace.metrics().incrementTerrainPasses();
            workspace.metrics().addTerrainBlockWrites(changedBlocks);
        }
        workspace.markDensityReady();
        workspace.markAquiferReady();
        return new TerrainPreparationResult(samples, decisions, changedBlocks);
    }

    public static long fillBiomes(GAChunkWorkspace workspace, BiomeSampler sampler) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (sampler == null) {
            throw new NullPointerException("sampler");
        }

        long start = System.nanoTime();
        long columns = 0L;
        workspace.ensureBiomeBuffer();
        int[] biomeIds = workspace.biomeIds();
        int column = 0;
        for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
            for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                biomeIds[column++] = sampler.biomeId(localX, localZ);
                columns++;
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        return columns;
    }

    public static long scanPreliminarySurfaceHeights(GAChunkWorkspace workspace, SurfaceBlockPredicate solidBlock) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (solidBlock == null) {
            throw new NullPointerException("solidBlock");
        }

        long start = System.nanoTime();
        long scannedColumns = 0L;
        workspace.ensureSurfaceBuffer();
        int blockCount = workspace.blockCount();
        int[] blocks = requireBlockBuffer(workspace, blockCount);
        int[] heights = workspace.heightCandidates();
        int[] surfaces = workspace.surfaceBlockIds();
        int minY = workspace.minBuildHeight();
        int maxLocalY = workspace.buildHeight() - 1;
        int maxY = minY + maxLocalY;
        for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
            for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                int column = columnIndex(localX, localZ);
                int height = GAChunkWorkspace.UNKNOWN_HEIGHT;
                int surfaceBlockId = GAChunkWorkspace.EMPTY_BLOCK_ID;
                for (int localY = maxLocalY, y = maxY; localY >= 0; localY--, y--) {
                    int blockId = blocks[(localY << 8) | column];
                    if (solidBlock.isSurfaceBlock(localX, y, localZ, blockId)) {
                        height = y;
                        surfaceBlockId = blockId;
                        break;
                    }
                }
                heights[column] = height;
                surfaces[column] = surfaceBlockId;
                scannedColumns++;
            }
        }
        workspace.markHeightCandidatesDirty();
        markAllColumnsDirty(workspace.dirtyHeightColumnWords());
        markAllColumnsDirty(workspace.dirtySurfaceColumnWords());
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().addSurfaceScannedColumns(scannedColumns);
        workspace.metrics().addHeightUpdates(scannedColumns);
        workspace.markSurfaceReady();
        return scannedColumns;
    }

    public static long fillCarverMask(GAChunkWorkspace workspace, CarverPredicate predicate) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (predicate == null) {
            throw new NullPointerException("predicate");
        }

        long start = System.nanoTime();
        long carved = 0L;
        int blockCount = workspace.blockCount();
        workspace.ensureCarverMaskCapacity(blockCount);
        int[] blocks = requireBlockBuffer(workspace, blockCount);
        long[] mask = workspace.carverMaskWords();
        int words = maskWords(blockCount);
        Arrays.fill(mask, 0, words, 0L);
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    if (predicate.shouldCarve(localX, y, localZ, blocks[index])) {
                        mask[index >>> 6] |= 1L << (index & 63);
                        carved++;
                    }
                    index++;
                }
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.markCarverReady();
        return carved;
    }

    public static long applyCarverMask(GAChunkWorkspace workspace, int airBlockId) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }

        long start = System.nanoTime();
        long changed = 0L;
        int blockCount = workspace.blockCount();
        int[] blocks = requireBlockBuffer(workspace, blockCount);
        long[] mask = workspace.carverMaskWords();
        int words = Math.min(mask.length, maskWords(blockCount));
        for (int wordIndex = 0; wordIndex < words; wordIndex++) {
            long word = mask[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                if (index >= blockCount) {
                    break;
                }
                if (blocks[index] != airBlockId) {
                    blocks[index] = airBlockId;
                    markDirtyBlock(workspace, index);
                    changed++;
                }
                word &= word - 1L;
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().addCarvedBlocks(changed);
        return changed;
    }

    public static CarverResult fillAndApplyCarverMask(
            GAChunkWorkspace workspace,
            CarverPredicate predicate,
            int airBlockId
    ) {
        if (workspace == null) {
            throw new NullPointerException("workspace");
        }
        if (predicate == null) {
            throw new NullPointerException("predicate");
        }

        long start = System.nanoTime();
        int blockCount = workspace.blockCount();
        int[] blocks = requireBlockBuffer(workspace, blockCount);
        workspace.ensureCarverMaskCapacity(blockCount);
        long[] mask = workspace.carverMaskWords();
        int words = maskWords(blockCount);
        Arrays.fill(mask, 0, words, 0L);

        long carved = 0L;
        long changed = 0L;
        int minY = workspace.minBuildHeight();
        int maxY = minY + workspace.buildHeight();
        int index = 0;
        for (int y = minY; y < maxY; y++) {
            for (int localZ = 0; localZ < GAChunkWorkspace.CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < GAChunkWorkspace.CHUNK_WIDTH; localX++) {
                    if (predicate.shouldCarve(localX, y, localZ, blocks[index])) {
                        mask[index >>> 6] |= 1L << (index & 63);
                        carved++;
                        if (blocks[index] != airBlockId) {
                            blocks[index] = airBlockId;
                            markDirtyBlock(workspace, index);
                            changed++;
                        }
                    }
                    index++;
                }
            }
        }
        workspace.metrics().addComputeNanos(System.nanoTime() - start);
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().incrementTerrainPasses();
        workspace.metrics().addCarvedBlocks(changed);
        workspace.markCarverReady();
        return new CarverResult(carved, changed);
    }

    @FunctionalInterface
    public interface DensitySampler {
        double density(int localX, int y, int localZ);
    }

    @FunctionalInterface
    public interface AquiferSampler {
        int blockId(int localX, int y, int localZ);
    }

    @FunctionalInterface
    public interface TerrainBlockResolver {
        int blockId(int localX, int y, int localZ, double density, int aquiferBlockId, int currentBlockId);
    }

    @FunctionalInterface
    public interface BiomeSampler {
        int biomeId(int localX, int localZ);
    }

    @FunctionalInterface
    public interface SurfaceBlockPredicate {
        boolean isSurfaceBlock(int localX, int y, int localZ, int blockId);
    }

    @FunctionalInterface
    public interface CarverPredicate {
        boolean shouldCarve(int localX, int y, int localZ, int blockId);
    }

    public record TerrainPreparationResult(long densitySamples, long aquiferDecisions, long terrainBlocksWritten) {
    }

    public record CarverResult(long carvedMaskBlocks, long carvedChangedBlocks) {
    }

    private static int[] requireBlockBuffer(GAChunkWorkspace workspace, int requiredInts) {
        int[] blocks = workspace.blockIds();
        if (!workspace.blockBufferEnabled() || blocks == null || workspace.blockCapacity() < requiredInts) {
            throw new IllegalStateException("block buffer is not allocated");
        }
        return blocks;
    }

    private static double[] requireDensityBuffer(GAChunkWorkspace workspace, int requiredDoubles) {
        double[] densities = workspace.densityBuffer();
        if (!workspace.densityBufferEnabled() || densities == null || workspace.densityCapacity() < requiredDoubles) {
            throw new IllegalStateException("density buffer is not allocated");
        }
        return densities;
    }

    private static int[] requireAquiferBuffer(GAChunkWorkspace workspace, int requiredInts) {
        int[] aquifers = workspace.aquiferBlockIds();
        if (!workspace.aquiferBufferEnabled() || aquifers == null || workspace.aquiferCapacity() < requiredInts) {
            throw new IllegalStateException("aquifer buffer is not allocated");
        }
        return aquifers;
    }

    private static int maskWords(int blockCount) {
        return Math.max(1, (blockCount + 63) >>> 6);
    }

    private static int columnIndex(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    private static void markDirtyBlock(GAChunkWorkspace workspace, int blockIndex) {
        int column = blockIndex & 255;
        int columnWord = column >>> 6;
        long columnBit = 1L << (column & 63);
        workspace.dirtyBlockColumnWords()[columnWord] |= columnBit;
        workspace.dirtyLightColumnWords()[columnWord] |= columnBit;

        int y = workspace.minBuildHeight() + (blockIndex >>> 8);
        int sectionIndex = Math.floorDiv(y, GAChunkWorkspace.CHUNK_WIDTH) - workspace.minSectionY();
        workspace.dirtySectionWords()[sectionIndex >>> 6] |= 1L << (sectionIndex & 63);
    }

    private static void markAllColumnsDirty(long[] columnWords) {
        Arrays.fill(columnWords, -1L);
    }
}
