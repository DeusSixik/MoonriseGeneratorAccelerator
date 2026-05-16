package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Arrays;

/**
 * Chunk-local storage prepared for future GA worldgen stages.
 *
 * <p>Phase 1 intentionally snapshots only cheap metadata by default. Dense block
 * ids are opt-in so integration can create workspaces without immediately
 * retaining full chunk-sized arrays.</p>
 */
public final class GAChunkWorkspace {
    public static final int CHUNK_WIDTH = 16;
    public static final int COLUMN_COUNT = CHUNK_WIDTH * CHUNK_WIDTH;
    public static final int BLOCKS_PER_SECTION = CHUNK_WIDTH * CHUNK_WIDTH * CHUNK_WIDTH;
    public static final int EMPTY_BLOCK_ID = 0;
    public static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    private static final int DEFAULT_MAX_RETAINED_BLOCK_INTS = BLOCKS_PER_SECTION * 24;
    private static final int DEFAULT_MAX_RETAINED_HEIGHT_INTS = COLUMN_COUNT;
    private static final int DEFAULT_MAX_RETAINED_DIRTY_WORDS = 16;

    private final int maxRetainedBlockInts;
    private final int maxRetainedHeightInts;
    private final int maxRetainedDirtyWords;
    private final GAChunkWorkspaceMetrics metrics = new GAChunkWorkspaceMetrics();

    private boolean active;
    private boolean imported;
    private int chunkX;
    private int chunkZ;
    private int minBlockX;
    private int minBlockZ;
    private int minBuildHeight;
    private int buildHeight;
    private int sectionCount;
    private int minSectionY;
    private int maxSectionY;

    private int[] blockIds;
    private int blockCapacity;
    private boolean blockBufferEnabled;
    private double[] densityBuffer;
    private int densityCapacity;
    private boolean densityBufferEnabled;
    private int[] aquiferBlockIds;
    private int aquiferCapacity;
    private boolean aquiferBufferEnabled;
    private int[] biomeIds = new int[COLUMN_COUNT];
    private boolean biomeBufferEnabled;
    private int[] surfaceBlockIds = new int[COLUMN_COUNT];
    private boolean surfaceBufferEnabled;
    private long[] carverMaskWords = new long[1];
    private int carverMaskCapacity;
    private boolean carverMaskEnabled;
    private int[] heightCandidates = new int[COLUMN_COUNT];
    private long[] dirtySectionWords = new long[1];
    private long[] dirtyBlockWords = new long[1];
    private long[] terrainSectionOnlyDirtyWords = new long[1];
    private long[] lazyAirInitializedSectionWords = new long[1];
    private long[] terrainSectionCounterKnownWords = new long[1];
    private short[] terrainNonEmptySectionCounts;
    private short[] terrainTickingBlockSectionCounts;
    private short[] terrainTickingFluidSectionCounts;
    private int[] terrainLightEmissionSectionCounts;
    private final long[] dirtyColumnWords = new long[4];
    private final long[] dirtyBlockColumnWords = new long[4];
    private final long[] dirtyHeightColumnWords = new long[4];
    private final long[] dirtySurfaceColumnWords = new long[4];
    private final long[] dirtyLightColumnWords = new long[4];
    private boolean densityReady;
    private boolean aquiferReady;
    private boolean surfaceReady;
    private boolean carverReady;
    private boolean terrainFinalized;
    private boolean heightCandidatesDirty;
    private boolean lazyAirBlockBuffer;
    private int lazyAirBlockId = EMPTY_BLOCK_ID;
    private long mirroredWrites;
    private long workspaceOnlyWrites;
    private long terrainWorkspaceOnlyWrites;

    public GAChunkWorkspace() {
        this(DEFAULT_MAX_RETAINED_BLOCK_INTS, DEFAULT_MAX_RETAINED_HEIGHT_INTS, DEFAULT_MAX_RETAINED_DIRTY_WORDS);
    }

    public GAChunkWorkspace(int maxRetainedBlockInts, int maxRetainedHeightInts, int maxRetainedDirtyWords) {
        if (maxRetainedBlockInts < 0 || maxRetainedHeightInts < COLUMN_COUNT || maxRetainedDirtyWords < 1) {
            throw new IllegalArgumentException("invalid workspace retention limits");
        }
        this.maxRetainedBlockInts = maxRetainedBlockInts;
        this.maxRetainedHeightInts = maxRetainedHeightInts;
        this.maxRetainedDirtyWords = maxRetainedDirtyWords;
        Arrays.fill(heightCandidates, UNKNOWN_HEIGHT);
    }

    public void begin(ChunkAccess chunk) {
        begin(chunk, false);
    }

    public void begin(ChunkAccess chunk, boolean allocateBlockBuffer) {
        if (chunk == null) {
            throw new NullPointerException("chunk");
        }
        resetForBegin();

        long start = System.nanoTime();
        importMetadata(chunk);
        metrics.addImportNanos(System.nanoTime() - start);

        if (allocateBlockBuffer) {
            ensureBlockBufferCapacity(requiredBlockCapacity());
            blockBufferEnabled = true;
            lazyAirBlockBuffer = false;
            clearLazyAirInitializedSections();
            Arrays.fill(blockIds, 0, blockCapacity, EMPTY_BLOCK_ID);
        }

        active = true;
        imported = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void reset() {
        clearRuntimeState();
        shrinkOversizedBuffers();
        clearMetadata();
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void release() {
        reset();
        active = false;
    }

    public void ensureBlockBufferCapacity(int requiredInts) {
        if (requiredInts < 0) {
            throw new IllegalArgumentException("requiredInts must be non-negative");
        }
        if (requiredInts == 0) {
            blockBufferEnabled = true;
            return;
        }
        if (blockIds == null || blockIds.length < requiredInts) {
            blockIds = new int[requiredInts];
        }
        blockCapacity = Math.max(blockCapacity, requiredInts);
        ensureDirtyBlockWordCapacity(Math.max(1, (blockCapacity + 63) >>> 6));
        blockBufferEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void initializeLazyAirBlockBuffer(int airBlockId) {
        requireImported();
        ensureBlockBufferCapacity(requiredBlockCapacity());
        lazyAirBlockBuffer = true;
        lazyAirBlockId = airBlockId;
        clearLazyAirInitializedSections();
        clearTerrainSectionCounters();
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void disableLazyAirBlockBuffer() {
        lazyAirBlockBuffer = false;
        lazyAirBlockId = EMPTY_BLOCK_ID;
        clearLazyAirInitializedSections();
        clearTerrainSectionCounters();
    }

    public void ensureDensityBufferCapacity(int requiredDoubles) {
        if (requiredDoubles < 0) {
            throw new IllegalArgumentException("requiredDoubles must be non-negative");
        }
        if (requiredDoubles > 0 && (densityBuffer == null || densityBuffer.length < requiredDoubles)) {
            densityBuffer = new double[requiredDoubles];
        }
        densityCapacity = Math.max(densityCapacity, requiredDoubles);
        densityBufferEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void ensureAquiferBufferCapacity(int requiredInts) {
        if (requiredInts < 0) {
            throw new IllegalArgumentException("requiredInts must be non-negative");
        }
        if (requiredInts > 0 && (aquiferBlockIds == null || aquiferBlockIds.length < requiredInts)) {
            aquiferBlockIds = new int[requiredInts];
        }
        aquiferCapacity = Math.max(aquiferCapacity, requiredInts);
        aquiferBufferEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void ensureBiomeBuffer() {
        biomeBufferEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void ensureSurfaceBuffer() {
        surfaceBufferEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public void ensureCarverMaskCapacity(int requiredBits) {
        if (requiredBits < 0) {
            throw new IllegalArgumentException("requiredBits must be non-negative");
        }
        int requiredWords = Math.max(1, (requiredBits + 63) >>> 6);
        if (carverMaskWords.length < requiredWords) {
            carverMaskWords = Arrays.copyOf(carverMaskWords, requiredWords);
        }
        carverMaskCapacity = Math.max(carverMaskCapacity, requiredBits);
        carverMaskEnabled = true;
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
    }

    public int blockIndex(int localX, int y, int localZ) {
        checkLocalColumn(localX, localZ);
        int localY = y - minBuildHeight;
        if (localY < 0 || localY >= buildHeight) {
            throw new IndexOutOfBoundsException("y outside workspace height: " + y);
        }
        return (localY << 8) | (localZ << 4) | localX;
    }

    public void setBlockId(int localX, int y, int localZ, int blockId) {
        requireBlockBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= blockCapacity) {
            throw new IndexOutOfBoundsException("block buffer is not sized for y=" + y);
        }
        setBlockIdRaw(index, blockId);
        markDirtyBlock(localX, y, localZ);
    }

    public boolean setBlockIdIfChanged(int localX, int y, int localZ, int blockId) {
        requireBlockBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= blockCapacity) {
            throw new IndexOutOfBoundsException("block buffer is not sized for y=" + y);
        }
        materializeLazyAirSection(sectionIndexForY(y));
        if (blockIds[index] == blockId) {
            return false;
        }
        setBlockIdRaw(index, blockId);
        markDirtyBlock(localX, y, localZ);
        return true;
    }

    public boolean setBlockIdMirroredIfChanged(int localX, int y, int localZ, int blockId) {
        boolean changed = setBlockIdIfChanged(localX, y, localZ, blockId);
        if (changed) {
            mirroredWrites++;
        }
        return changed;
    }

    public boolean setBlockIdWorkspaceOnlyIfChanged(int localX, int y, int localZ, int blockId) {
        boolean changed = setBlockIdIfChanged(localX, y, localZ, blockId);
        if (changed) {
            workspaceOnlyWrites++;
        }
        return changed;
    }

    /**
     * Hot terrain path for callers that already resolved chunk-local indexes.
     * Returns true when the write is safely owned by the workspace, even if the
     * block id was already present and no dirty diff had to be recorded.
     */
    public boolean writeTerrainBlockIdWorkspaceOnly(
            int workspaceIndex,
            int sectionIndex,
            int columnIndex,
            int y,
            int blockId
    ) {
        if (!blockBufferEnabled || blockIds == null || blockId < 0
                || workspaceIndex < 0 || workspaceIndex >= blockCapacity
                || sectionIndex < 0 || sectionIndex >= sectionCount
                || columnIndex < 0 || columnIndex >= COLUMN_COUNT) {
            return false;
        }

        materializeLazyAirSection(sectionIndex);
        if (blockIds[workspaceIndex] != blockId) {
            blockIds[workspaceIndex] = blockId;
            markDirtyBlockIndex(workspaceIndex);
            markDirtySection(sectionIndex);
            markDirtyBlockColumnIndex(columnIndex);
            markDirtyLightColumnIndex(columnIndex);
            workspaceOnlyWrites++;
            terrainWorkspaceOnlyWrites++;
        }
        markDirtyHeightColumnIndex(columnIndex);
        markDirtySurfaceColumnIndex(columnIndex);
        markDirtyLightColumnIndex(columnIndex);
        int height = heightCandidates[columnIndex];
        if (height == UNKNOWN_HEIGHT || y >= height) {
            heightCandidates[columnIndex] = y;
            heightCandidatesDirty = true;
            metrics.addHeightUpdates(1L);
            if (surfaceBufferEnabled) {
                surfaceBlockIds[columnIndex] = blockId;
            }
        }
        return true;
    }

    /**
     * Terrain-only hot path for local final repack. It dirties whole terrain
     * sections instead of recording per-block diff bits, so finalization can
     * publish the section by one raw copy without risking missed terrain blocks.
     */
    public boolean writeTerrainBlockIdWorkspaceOnlySectionDirty(
            int workspaceIndex,
            int sectionIndex,
            int blockId
    ) {
        if (!blockBufferEnabled || blockIds == null || blockId < 0
                || workspaceIndex < 0 || workspaceIndex >= blockCapacity
                || sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }

        materializeLazyAirSection(sectionIndex);
        ensureTerrainSectionCountersKnown(sectionIndex);
        int oldBlockId = blockIds[workspaceIndex];
        if (oldBlockId != blockId) {
            if (oldBlockId == lazyAirBlockId) {
                incrementTerrainSectionCountsFromAir(sectionIndex, blockId);
            } else {
                updateTerrainSectionCounts(sectionIndex, oldBlockId, blockId);
            }
            blockIds[workspaceIndex] = blockId;
            markDirtySection(sectionIndex);
            markTerrainSectionOnlyDirty(sectionIndex);
            workspaceOnlyWrites++;
            terrainWorkspaceOnlyWrites++;
        }
        return true;
    }

    /**
     * Prepare a section once for the terrain section-dirty hot path. Callers
     * can then use writePreparedTerrainBlockIdWorkspaceOnlySectionDirty without
     * paying lazy-air materialization and counter setup on every block write.
     */
    public boolean prepareTerrainBlockIdWorkspaceOnlySection(int sectionIndex) {
        if (!blockBufferEnabled || blockIds == null || sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }

        materializeLazyAirSection(sectionIndex);
        ensureTerrainSectionCountersKnown(sectionIndex);
        return true;
    }

    public boolean writePreparedTerrainBlockIdWorkspaceOnlySectionDirty(
            int workspaceIndex,
            int sectionIndex,
            int blockId
    ) {
        if (!blockBufferEnabled || blockIds == null || blockId < 0
                || workspaceIndex < 0 || workspaceIndex >= blockCapacity
                || sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }

        int oldBlockId = blockIds[workspaceIndex];
        if (oldBlockId != blockId) {
            if (oldBlockId == lazyAirBlockId) {
                incrementTerrainSectionCountsFromAir(sectionIndex, blockId);
            } else {
                updateTerrainSectionCounts(sectionIndex, oldBlockId, blockId);
            }
            blockIds[workspaceIndex] = blockId;
            markDirtySection(sectionIndex);
            markTerrainSectionOnlyDirty(sectionIndex);
            workspaceOnlyWrites++;
            terrainWorkspaceOnlyWrites++;
        }
        return true;
    }

    public boolean commitPreparedTerrainSectionOnlyWrites(
            int sectionIndex,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            int lightEmissionCount,
            int writtenBlocks
    ) {
        if (!blockBufferEnabled || blockIds == null || sectionIndex < 0 || sectionIndex >= sectionCount
                || writtenBlocks <= 0) {
            return false;
        }

        setTerrainSectionCounts(
                sectionIndex,
                Math.max(0, Math.min(BLOCKS_PER_SECTION, nonEmptyBlockCount)),
                Math.max(0, Math.min(BLOCKS_PER_SECTION, tickingBlockCount)),
                Math.max(0, Math.min(BLOCKS_PER_SECTION, tickingFluidCount)),
                Math.max(0, Math.min(BLOCKS_PER_SECTION, lightEmissionCount))
        );
        markDirtySection(sectionIndex);
        markTerrainSectionOnlyDirty(sectionIndex);
        workspaceOnlyWrites += writtenBlocks;
        terrainWorkspaceOnlyWrites += writtenBlocks;
        return true;
    }

    public int blockId(int localX, int y, int localZ) {
        requireBlockBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= blockCapacity) {
            throw new IndexOutOfBoundsException("block buffer is not sized for y=" + y);
        }
        if (lazyAirBlockBuffer && !isLazyAirSectionInitialized(sectionIndexForY(y))) {
            return lazyAirBlockId;
        }
        return blockIds[index];
    }

    public void setDensity(int localX, int y, int localZ, double density) {
        requireDensityBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= densityCapacity) {
            throw new IndexOutOfBoundsException("density buffer is not sized for y=" + y);
        }
        densityBuffer[index] = density;
    }

    public double density(int localX, int y, int localZ) {
        requireDensityBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= densityCapacity) {
            throw new IndexOutOfBoundsException("density buffer is not sized for y=" + y);
        }
        return densityBuffer[index];
    }

    public void setAquiferBlockId(int localX, int y, int localZ, int blockId) {
        requireAquiferBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= aquiferCapacity) {
            throw new IndexOutOfBoundsException("aquifer buffer is not sized for y=" + y);
        }
        aquiferBlockIds[index] = blockId;
    }

    public int aquiferBlockId(int localX, int y, int localZ) {
        requireAquiferBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= aquiferCapacity) {
            throw new IndexOutOfBoundsException("aquifer buffer is not sized for y=" + y);
        }
        return aquiferBlockIds[index];
    }

    public void setBiomeId(int localX, int localZ, int biomeId) {
        requireBiomeBuffer();
        biomeIds[columnIndex(localX, localZ)] = biomeId;
    }

    public int biomeId(int localX, int localZ) {
        requireBiomeBuffer();
        return biomeIds[columnIndex(localX, localZ)];
    }

    public void setSurfaceBlockId(int localX, int localZ, int blockId) {
        requireSurfaceBuffer();
        surfaceBlockIds[columnIndex(localX, localZ)] = blockId;
        markDirtySurfaceColumn(localX, localZ);
    }

    public int surfaceBlockId(int localX, int localZ) {
        requireSurfaceBuffer();
        return surfaceBlockIds[columnIndex(localX, localZ)];
    }

    public void setCarverMask(int localX, int y, int localZ, boolean carved) {
        requireCarverMask();
        int index = blockIndex(localX, y, localZ);
        if (index >= carverMaskCapacity) {
            throw new IndexOutOfBoundsException("carver mask is not sized for y=" + y);
        }
        int word = index >>> 6;
        long bit = 1L << index;
        if (carved) {
            carverMaskWords[word] |= bit;
        } else {
            carverMaskWords[word] &= ~bit;
        }
    }

    public boolean carverMask(int localX, int y, int localZ) {
        requireCarverMask();
        int index = blockIndex(localX, y, localZ);
        if (index >= carverMaskCapacity) {
            throw new IndexOutOfBoundsException("carver mask is not sized for y=" + y);
        }
        return (carverMaskWords[index >>> 6] & (1L << index)) != 0L;
    }

    public long importBlockIds(BlockIdReader reader) {
        if (reader == null) {
            throw new NullPointerException("reader");
        }
        requireImported();
        long start = System.nanoTime();
        long importedBlocks = 0L;
        ensureBlockBufferCapacity(requiredBlockCapacity());
        lazyAirBlockBuffer = false;
        clearLazyAirInitializedSections();
        clearTerrainSectionCounters();
        int index = 0;
        int maxY = minBuildHeight + buildHeight;
        for (int y = minBuildHeight; y < maxY; y++) {
            for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                    blockIds[index++] = reader.blockId(localX, y, localZ);
                    importedBlocks++;
                }
            }
        }
        clearDirtySections();
        clearDirtyBlocks();
        clearDirtyBlockColumns();
        clearTerrainSectionOnlyDirties();
        metrics.addImportNanos(System.nanoTime() - start);
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
        return importedBlocks;
    }

    public long repackDirtyBlockIds(BlockIdWriter writer) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }
        requireBlockBuffer();
        long start = System.nanoTime();
        long writtenBlocks = 0L;
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            if (!isDirtySection(sectionIndex)) {
                continue;
            }
            int sectionY = minSectionY + sectionIndex;
            int sectionMinY = sectionY * CHUNK_WIDTH;
            int minY = Math.max(minBuildHeight, sectionMinY);
            int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
            for (int y = minY; y < maxY; y++) {
                int baseIndex = (y - minBuildHeight) << 8;
                for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                    int rowIndex = baseIndex | (localZ << 4);
                    for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                        writer.writeBlockId(localX, y, localZ, blockIds[rowIndex | localX]);
                        writtenBlocks++;
                    }
                }
            }
        }
        clearDirtySections();
        clearDirtyBlocks();
        clearDirtyBlockColumns();
        clearTerrainSectionOnlyDirties();
        metrics.addRepackNanos(System.nanoTime() - start);
        return writtenBlocks;
    }

    public long repackDirtyBlockSection(int sectionIndex, BlockIdWriter writer) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }
        requireBlockBuffer();
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        if (!isDirtySection(sectionIndex)) {
            return 0L;
        }

        long start = System.nanoTime();
        long writtenBlocks = 0L;
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        for (int y = minY; y < maxY; y++) {
            int baseIndex = (y - minBuildHeight) << 8;
            for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                int rowIndex = baseIndex | (localZ << 4);
                for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                    writer.writeBlockId(localX, y, localZ, blockIds[rowIndex | localX]);
                    writtenBlocks++;
                }
            }
        }
        clearDirtySection(sectionIndex);
        clearDirtyBlockSection(sectionIndex);
        clearTerrainSectionOnlyDirty(sectionIndex);
        metrics.addRepackNanos(System.nanoTime() - start);
        return writtenBlocks;
    }

    public long repackDirtyBlockRunsInSection(int sectionIndex, DirtyBlockRunWriter writer) {
        if (writer == null) {
            throw new NullPointerException("writer");
        }
        requireBlockBuffer();
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        if (!isDirtySection(sectionIndex) || !hasDirtyBlocksInSection(sectionIndex)) {
            return 0L;
        }

        long start = System.nanoTime();
        long writtenBlocks = 0L;
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        int runSectionLocalIndex = -1;
        int runWorkspaceIndex = -1;
        int runLength = 0;

        for (int y = minY; y < maxY; y++) {
            int workspaceBaseIndex = (y - minBuildHeight) << 8;
            int sectionBaseIndex = (y & 15) << 8;
            for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                int workspaceRowIndex = workspaceBaseIndex | (localZ << 4);
                int sectionRowIndex = sectionBaseIndex | (localZ << 4);
                for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                    int workspaceIndex = workspaceRowIndex | localX;
                    if (!isDirtyBlockIndex(workspaceIndex)) {
                        if (runLength > 0) {
                            writer.writeRun(runSectionLocalIndex, runWorkspaceIndex, runLength);
                            writtenBlocks += runLength;
                            runLength = 0;
                        }
                        continue;
                    }

                    int sectionLocalIndex = sectionRowIndex | localX;
                    if (runLength == 0) {
                        runSectionLocalIndex = sectionLocalIndex;
                        runWorkspaceIndex = workspaceIndex;
                        runLength = 1;
                    } else if (workspaceIndex == runWorkspaceIndex + runLength
                            && sectionLocalIndex == runSectionLocalIndex + runLength) {
                        runLength++;
                    } else {
                        writer.writeRun(runSectionLocalIndex, runWorkspaceIndex, runLength);
                        writtenBlocks += runLength;
                        runSectionLocalIndex = sectionLocalIndex;
                        runWorkspaceIndex = workspaceIndex;
                        runLength = 1;
                    }
                }
            }
        }

        if (runLength > 0) {
            writer.writeRun(runSectionLocalIndex, runWorkspaceIndex, runLength);
            writtenBlocks += runLength;
        }

        clearDirtyBlockSection(sectionIndex);
        clearDirtySection(sectionIndex);
        clearTerrainSectionOnlyDirty(sectionIndex);
        metrics.addRepackNanos(System.nanoTime() - start);
        return writtenBlocks;
    }

    public long copyDirtyBlockSectionToRaw(int sectionIndex, int[] target) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        requireBlockBuffer();
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        if (target.length < BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("target section buffer is too small");
        }
        if (!isDirtySection(sectionIndex)) {
            return 0L;
        }

        long start = System.nanoTime();
        System.arraycopy(blockIds, sectionIndex * BLOCKS_PER_SECTION, target, 0, BLOCKS_PER_SECTION);
        clearDirtySection(sectionIndex);
        clearDirtyBlockSection(sectionIndex);
        clearTerrainSectionOnlyDirty(sectionIndex);
        metrics.addRepackNanos(System.nanoTime() - start);
        return BLOCKS_PER_SECTION;
    }

    public void clearCommittedBlockDirties() {
        clearDirtySections();
        clearDirtyBlocks();
        clearDirtyBlockColumns();
        clearTerrainSectionOnlyDirties();
    }

    public void setHeightCandidate(int localX, int localZ, int y) {
        heightCandidates[columnIndex(localX, localZ)] = y;
        heightCandidatesDirty = true;
        markDirtyHeightColumn(localX, localZ);
        metrics.addHeightUpdates(1L);
    }

    public int heightCandidate(int localX, int localZ) {
        return heightCandidates[columnIndex(localX, localZ)];
    }

    public void markDirtyBlock(int localX, int y, int localZ) {
        markDirtyBlockIndex(blockIndex(localX, y, localZ));
        markDirtyBlockColumn(localX, localZ);
        markDirtyLightColumn(localX, localZ);
        markDirtySection(sectionIndexForY(y));
    }

    public void markDirtySection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        int word = sectionIndex >>> 6;
        ensureDirtySectionWordCapacity(word + 1);
        dirtySectionWords[word] |= 1L << sectionIndex;
    }

    public void markDirtyColumn(int localX, int localZ) {
        markDirtyBlockColumn(localX, localZ);
        markDirtyHeightColumn(localX, localZ);
        markDirtySurfaceColumn(localX, localZ);
        markDirtyLightColumn(localX, localZ);
    }

    public void markDirtyBlockColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        markDirtyBlockColumnIndex(index);
    }

    public void markDirtyHeightColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        markDirtyHeightColumnIndex(index);
    }

    public void markDirtySurfaceColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        markDirtySurfaceColumnIndex(index);
    }

    public void markDirtyLightColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        markDirtyLightColumnIndex(index);
    }

    public boolean isDirtySection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }
        int word = sectionIndex >>> 6;
        return word < dirtySectionWords.length && (dirtySectionWords[word] & (1L << sectionIndex)) != 0L;
    }

    public boolean hasDirtySections() {
        int words = Math.min(dirtySectionWords.length, Math.max(1, (sectionCount + 63) >>> 6));
        for (int word = 0; word < words; word++) {
            long mask = dirtySectionWords[word];
            if (word == words - 1 && (sectionCount & 63) != 0) {
                mask &= (1L << (sectionCount & 63)) - 1L;
            }
            if (mask != 0L) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDirtyBlocksInSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount || dirtyBlockWords.length == 0) {
            return false;
        }
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        if (minY >= maxY) {
            return false;
        }
        int firstIndex = (minY - minBuildHeight) << 8;
        int lastIndexExclusive = ((maxY - minBuildHeight) << 8);
        int firstWord = firstIndex >>> 6;
        int lastWord = (lastIndexExclusive - 1) >>> 6;
        for (int word = firstWord; word <= lastWord && word < dirtyBlockWords.length; word++) {
            long mask = dirtyBlockWords[word];
            if (word == firstWord) {
                mask &= -1L << firstIndex;
            }
            if (word == lastWord && (lastIndexExclusive & 63) != 0) {
                mask &= (1L << (lastIndexExclusive & 63)) - 1L;
            }
            if (mask != 0L) {
                return true;
            }
        }
        return false;
    }

    public int dirtyBlockCountInSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount || dirtyBlockWords.length == 0) {
            return 0;
        }
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        if (minY >= maxY) {
            return 0;
        }

        int firstIndex = (minY - minBuildHeight) << 8;
        int lastIndexExclusive = (maxY - minBuildHeight) << 8;
        int firstWord = firstIndex >>> 6;
        int lastWord = (lastIndexExclusive - 1) >>> 6;
        int count = 0;
        for (int word = firstWord; word <= lastWord && word < dirtyBlockWords.length; word++) {
            long mask = -1L;
            if (word == firstWord) {
                mask &= -1L << (firstIndex & 63);
            }
            if (word == lastWord && (lastIndexExclusive & 63) != 0) {
                mask &= (1L << (lastIndexExclusive & 63)) - 1L;
            }
            count += Long.bitCount(dirtyBlockWords[word] & mask);
        }
        return count;
    }

    public int[] dirtySectionIndices() {
        int count = 0;
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            if (isDirtySection(sectionIndex)) {
                count++;
            }
        }
        int[] dirty = new int[count];
        int output = 0;
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            if (isDirtySection(sectionIndex)) {
                dirty[output++] = sectionIndex;
            }
        }
        return dirty;
    }

    public boolean isDirtyColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return ((dirtyBlockColumnWords[index >>> 6] | dirtyHeightColumnWords[index >>> 6]
                | dirtySurfaceColumnWords[index >>> 6] | dirtyLightColumnWords[index >>> 6]) & (1L << index)) != 0L;
    }

    public boolean isDirtyBlockColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return (dirtyBlockColumnWords[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean isDirtyHeightColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return (dirtyHeightColumnWords[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean isDirtySurfaceColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return (dirtySurfaceColumnWords[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean isDirtyLightColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return (dirtyLightColumnWords[index >>> 6] & (1L << index)) != 0L;
    }

    public void clearCommittedBlockDirtiesInSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        clearDirtySection(sectionIndex);
        clearDirtyBlockSection(sectionIndex);
        clearTerrainSectionOnlyDirty(sectionIndex);
    }

    public void clearCommittedTerrainSectionOnlyDirtiesInSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        clearDirtySection(sectionIndex);
        clearTerrainSectionOnlyDirty(sectionIndex);
    }

    public long estimatedRetainedBytes() {
        long bytes = 16L; // object/header approximation anchor
        bytes += retainedIntBytes(blockIds);
        bytes += retainedDoubleBytes(densityBuffer);
        bytes += retainedIntBytes(aquiferBlockIds);
        bytes += retainedIntBytes(biomeIds);
        bytes += retainedIntBytes(surfaceBlockIds);
        bytes += retainedLongBytes(carverMaskWords);
        bytes += retainedIntBytes(heightCandidates);
        bytes += retainedLongBytes(dirtySectionWords);
        bytes += retainedLongBytes(dirtyBlockWords);
        bytes += retainedLongBytes(terrainSectionOnlyDirtyWords);
        bytes += retainedLongBytes(lazyAirInitializedSectionWords);
        bytes += retainedLongBytes(terrainSectionCounterKnownWords);
        bytes += retainedShortBytes(terrainNonEmptySectionCounts);
        bytes += retainedShortBytes(terrainTickingBlockSectionCounts);
        bytes += retainedShortBytes(terrainTickingFluidSectionCounts);
        bytes += retainedIntBytes(terrainLightEmissionSectionCounts);
        bytes += retainedLongBytes(dirtyColumnWords);
        bytes += retainedLongBytes(dirtyBlockColumnWords);
        bytes += retainedLongBytes(dirtyHeightColumnWords);
        bytes += retainedLongBytes(dirtySurfaceColumnWords);
        bytes += retainedLongBytes(dirtyLightColumnWords);
        return bytes;
    }

    public long mirroredWrites() {
        return mirroredWrites;
    }

    public long workspaceOnlyWrites() {
        return workspaceOnlyWrites;
    }

    public boolean hasWorkspaceOnlyWrites() {
        return workspaceOnlyWrites > 0L;
    }

    public long terrainWorkspaceOnlyWrites() {
        return terrainWorkspaceOnlyWrites;
    }

    public boolean hasOnlyTerrainWorkspaceOnlyWrites() {
        return workspaceOnlyWrites > 0L && workspaceOnlyWrites == terrainWorkspaceOnlyWrites;
    }

    public boolean hasTerrainSectionOnlyDirtiesInSection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }
        int word = sectionIndex >>> 6;
        return word < terrainSectionOnlyDirtyWords.length
                && (terrainSectionOnlyDirtyWords[word] & (1L << (sectionIndex & 63))) != 0L;
    }

    public boolean lazyAirBlockBuffer() {
        return lazyAirBlockBuffer;
    }

    public boolean isLazyAirSectionInitialized(int sectionIndex) {
        if (!lazyAirBlockBuffer) {
            return true;
        }
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }
        int word = sectionIndex >>> 6;
        return word < lazyAirInitializedSectionWords.length
                && (lazyAirInitializedSectionWords[word] & (1L << (sectionIndex & 63))) != 0L;
    }

    public boolean hasKnownTerrainSectionCounts(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }
        int word = sectionIndex >>> 6;
        return word < terrainSectionCounterKnownWords.length
                && (terrainSectionCounterKnownWords[word] & (1L << (sectionIndex & 63))) != 0L;
    }

    public int terrainNonEmptyBlockCountInSection(int sectionIndex) {
        return terrainNonEmptySectionCounts == null ? 0 : terrainNonEmptySectionCounts[sectionIndex] & 0xFFFF;
    }

    public int terrainTickingBlockCountInSection(int sectionIndex) {
        return terrainTickingBlockSectionCounts == null ? 0 : terrainTickingBlockSectionCounts[sectionIndex] & 0xFFFF;
    }

    public int terrainTickingFluidCountInSection(int sectionIndex) {
        return terrainTickingFluidSectionCounts == null ? 0 : terrainTickingFluidSectionCounts[sectionIndex] & 0xFFFF;
    }

    public int terrainLightEmissionCountInSection(int sectionIndex) {
        return terrainLightEmissionSectionCounts == null ? 0 : terrainLightEmissionSectionCounts[sectionIndex];
    }

    public GAChunkWorkspaceMetrics metrics() {
        metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
        return metrics;
    }

    public boolean active() {
        return active;
    }

    public boolean imported() {
        return imported;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int minBlockX() {
        return minBlockX;
    }

    public int minBlockZ() {
        return minBlockZ;
    }

    public int minBuildHeight() {
        return minBuildHeight;
    }

    public int buildHeight() {
        return buildHeight;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public int minSectionY() {
        return minSectionY;
    }

    public int maxSectionY() {
        return maxSectionY;
    }

    public boolean blockBufferEnabled() {
        return blockBufferEnabled;
    }

    public int blockCapacity() {
        return blockCapacity;
    }

    public int blockCount() {
        return buildHeight <= 0 ? 0 : buildHeight * COLUMN_COUNT;
    }

    public int[] blockIds() {
        return blockIds;
    }

    public boolean densityBufferEnabled() {
        return densityBufferEnabled;
    }

    public int densityCapacity() {
        return densityCapacity;
    }

    public double[] densityBuffer() {
        return densityBuffer;
    }

    public boolean aquiferBufferEnabled() {
        return aquiferBufferEnabled;
    }

    public int aquiferCapacity() {
        return aquiferCapacity;
    }

    public int[] aquiferBlockIds() {
        return aquiferBlockIds;
    }

    public boolean biomeBufferEnabled() {
        return biomeBufferEnabled;
    }

    public int[] biomeIds() {
        return biomeIds;
    }

    public boolean surfaceBufferEnabled() {
        return surfaceBufferEnabled;
    }

    public int[] surfaceBlockIds() {
        return surfaceBlockIds;
    }

    public boolean carverMaskEnabled() {
        return carverMaskEnabled;
    }

    public int carverMaskCapacity() {
        return carverMaskCapacity;
    }

    public long[] carverMaskWords() {
        return carverMaskWords;
    }

    public int[] heightCandidates() {
        return heightCandidates;
    }

    public boolean densityReady() {
        return densityReady;
    }

    public boolean aquiferReady() {
        return aquiferReady;
    }

    public boolean surfaceReady() {
        return surfaceReady;
    }

    public boolean carverReady() {
        return carverReady;
    }

    public boolean terrainFinalized() {
        return terrainFinalized;
    }

    public void markDensityReady() {
        densityReady = true;
        terrainFinalized = false;
    }

    public void markAquiferReady() {
        aquiferReady = true;
        terrainFinalized = false;
    }

    public void markSurfaceReady() {
        surfaceReady = true;
        terrainFinalized = false;
    }

    public void markCarverReady() {
        carverReady = true;
        terrainFinalized = false;
    }

    public void markTerrainFinalized() {
        terrainFinalized = true;
        metrics.incrementFinalizedWorkspaces();
    }

    void markHeightCandidatesDirty() {
        heightCandidatesDirty = true;
    }

    public long[] dirtySectionWords() {
        return dirtySectionWords;
    }

    public long[] dirtyBlockWords() {
        return dirtyBlockWords;
    }

    public long[] dirtyColumnWords() {
        mergeDirtyColumnWords();
        return dirtyColumnWords;
    }

    public long[] dirtyBlockColumnWords() {
        return dirtyBlockColumnWords;
    }

    public long[] dirtyHeightColumnWords() {
        return dirtyHeightColumnWords;
    }

    public long[] dirtySurfaceColumnWords() {
        return dirtySurfaceColumnWords;
    }

    public long[] dirtyLightColumnWords() {
        return dirtyLightColumnWords;
    }

    private void importMetadata(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        chunkX = pos.x;
        chunkZ = pos.z;
        minBlockX = pos.getMinBlockX();
        minBlockZ = pos.getMinBlockZ();
        minBuildHeight = chunk.getMinBuildHeight();
        buildHeight = chunk.getHeight();
        sectionCount = chunk.getSectionsCount();
        minSectionY = chunk.getMinSection();
        maxSectionY = chunk.getMaxSection();
        ensureDirtySectionWordCapacity(Math.max(1, (sectionCount + 63) >>> 6));
        ensureLazyAirSectionWordCapacity(Math.max(1, (sectionCount + 63) >>> 6));
        ensureTerrainSectionCounterWordCapacity(Math.max(1, (sectionCount + 63) >>> 6));
    }

    private void resetForBegin() {
        clearRuntimeState();
        clearMetadata();
        metrics.clear();
    }

    private void clearRuntimeState() {
        if (carverMaskEnabled) {
            clearCarverMask();
        }
        if (biomeBufferEnabled) {
            Arrays.fill(biomeIds, 0);
        }
        if (surfaceBufferEnabled) {
            Arrays.fill(surfaceBlockIds, EMPTY_BLOCK_ID);
        }
        if (heightCandidatesDirty) {
            Arrays.fill(heightCandidates, UNKNOWN_HEIGHT);
        }
        blockBufferEnabled = false;
        blockCapacity = 0;
        densityBufferEnabled = false;
        densityCapacity = 0;
        aquiferBufferEnabled = false;
        aquiferCapacity = 0;
        biomeBufferEnabled = false;
        surfaceBufferEnabled = false;
        carverMaskEnabled = false;
        carverMaskCapacity = 0;
        clearDirtySections();
        clearDirtyBlocks();
        clearTerrainSectionOnlyDirties();
        clearLazyAirInitializedSections();
        clearTerrainSectionCounters();
        clearDirtyColumns();
        densityReady = false;
        aquiferReady = false;
        surfaceReady = false;
        carverReady = false;
        terrainFinalized = false;
        heightCandidatesDirty = false;
        lazyAirBlockBuffer = false;
        lazyAirBlockId = EMPTY_BLOCK_ID;
        mirroredWrites = 0L;
        workspaceOnlyWrites = 0L;
        terrainWorkspaceOnlyWrites = 0L;
        imported = false;
    }

    private void clearMetadata() {
        chunkX = 0;
        chunkZ = 0;
        minBlockX = 0;
        minBlockZ = 0;
        minBuildHeight = 0;
        buildHeight = 0;
        sectionCount = 0;
        minSectionY = 0;
        maxSectionY = 0;
    }

    private void shrinkOversizedBuffers() {
        if (blockIds != null && blockIds.length > maxRetainedBlockInts) {
            blockIds = null;
        }
        if (densityBuffer != null && densityBuffer.length > maxRetainedBlockInts) {
            densityBuffer = null;
        }
        if (aquiferBlockIds != null && aquiferBlockIds.length > maxRetainedBlockInts) {
            aquiferBlockIds = null;
        }
        if (heightCandidates.length > maxRetainedHeightInts) {
            heightCandidates = new int[COLUMN_COUNT];
            Arrays.fill(heightCandidates, UNKNOWN_HEIGHT);
            heightCandidatesDirty = false;
        }
        if (dirtySectionWords.length > maxRetainedDirtyWords) {
            dirtySectionWords = new long[Math.max(1, maxRetainedDirtyWords)];
        }
        if (terrainSectionOnlyDirtyWords.length > maxRetainedDirtyWords) {
            terrainSectionOnlyDirtyWords = new long[Math.max(1, maxRetainedDirtyWords)];
        }
        if (lazyAirInitializedSectionWords.length > maxRetainedDirtyWords) {
            lazyAirInitializedSectionWords = new long[Math.max(1, maxRetainedDirtyWords)];
        }
        if (terrainSectionCounterKnownWords.length > maxRetainedDirtyWords) {
            terrainSectionCounterKnownWords = new long[Math.max(1, maxRetainedDirtyWords)];
        }
        int maxRetainedDirtyBlockWords = Math.max(1, maxRetainedBlockInts >>> 6);
        if (dirtyBlockWords.length > maxRetainedDirtyBlockWords) {
            dirtyBlockWords = new long[Math.max(1, maxRetainedDirtyBlockWords)];
        }
        if (carverMaskWords.length > maxRetainedDirtyWords) {
            carverMaskWords = new long[Math.max(1, maxRetainedDirtyWords)];
        }
    }

    private int requiredBlockCapacity() {
        return sectionCount <= 0 ? 0 : sectionCount * BLOCKS_PER_SECTION;
    }

    private int sectionIndexForY(int y) {
        int sectionY = Math.floorDiv(y, CHUNK_WIDTH);
        int index = sectionY - minSectionY;
        if (index < 0 || index >= sectionCount) {
            throw new IndexOutOfBoundsException("y outside workspace sections: " + y);
        }
        return index;
    }

    private int columnIndex(int localX, int localZ) {
        checkLocalColumn(localX, localZ);
        return (localZ << 4) | localX;
    }

    private static void checkLocalColumn(int localX, int localZ) {
        if ((localX | localZ) < 0 || localX >= CHUNK_WIDTH || localZ >= CHUNK_WIDTH) {
            throw new IndexOutOfBoundsException("local column outside chunk: " + localX + "," + localZ);
        }
    }

    private void ensureDirtySectionWordCapacity(int requiredWords) {
        if (dirtySectionWords.length < requiredWords) {
            dirtySectionWords = Arrays.copyOf(dirtySectionWords, requiredWords);
        }
        if (terrainSectionOnlyDirtyWords.length < requiredWords) {
            terrainSectionOnlyDirtyWords = Arrays.copyOf(terrainSectionOnlyDirtyWords, requiredWords);
        }
    }

    private void ensureDirtyBlockWordCapacity(int requiredWords) {
        if (dirtyBlockWords.length < requiredWords) {
            dirtyBlockWords = Arrays.copyOf(dirtyBlockWords, requiredWords);
        }
    }

    private void ensureLazyAirSectionWordCapacity(int requiredWords) {
        if (lazyAirInitializedSectionWords.length < requiredWords) {
            lazyAirInitializedSectionWords = Arrays.copyOf(lazyAirInitializedSectionWords, requiredWords);
        }
    }

    private void ensureTerrainSectionCounterWordCapacity(int requiredWords) {
        if (terrainSectionCounterKnownWords.length < requiredWords) {
            terrainSectionCounterKnownWords = Arrays.copyOf(terrainSectionCounterKnownWords, requiredWords);
        }
    }

    private void ensureTerrainSectionCounterCapacity() {
        if (terrainNonEmptySectionCounts == null || terrainNonEmptySectionCounts.length < sectionCount) {
            terrainNonEmptySectionCounts = new short[sectionCount];
            terrainTickingBlockSectionCounts = new short[sectionCount];
            terrainTickingFluidSectionCounts = new short[sectionCount];
            terrainLightEmissionSectionCounts = new int[sectionCount];
            clearTerrainSectionCounterKnownWords();
            metrics.setEstimatedRetainedBytes(estimatedRetainedBytes());
        }
    }

    private void materializeLazyAirSectionForWorkspaceIndex(int workspaceIndex) {
        if (!lazyAirBlockBuffer) {
            return;
        }
        int y = minBuildHeight + (workspaceIndex >>> 8);
        materializeLazyAirSection(sectionIndexForY(y));
    }

    private void materializeLazyAirSection(int sectionIndex) {
        if (!lazyAirBlockBuffer || isLazyAirSectionInitialized(sectionIndex)) {
            return;
        }
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("section index outside workspace: " + sectionIndex);
        }
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        if (minY < maxY) {
            int firstIndex = (minY - minBuildHeight) << 8;
            int lastIndexExclusive = (maxY - minBuildHeight) << 8;
            Arrays.fill(blockIds, firstIndex, lastIndexExclusive, lazyAirBlockId);
            GAChunkWorkspaceMetrics.incrementTerrainLazyAirSectionClears();
        }
        setTerrainSectionCounts(sectionIndex, 0, 0, 0, 0);
        markLazyAirSectionInitialized(sectionIndex);
    }

    private void markLazyAirSectionInitialized(int sectionIndex) {
        int word = sectionIndex >>> 6;
        ensureLazyAirSectionWordCapacity(word + 1);
        lazyAirInitializedSectionWords[word] |= 1L << (sectionIndex & 63);
    }

    private void clearLazyAirInitializedSections() {
        Arrays.fill(lazyAirInitializedSectionWords, 0L);
    }

    private void clearTerrainSectionCounters() {
        if (terrainNonEmptySectionCounts != null) {
            Arrays.fill(terrainNonEmptySectionCounts, (short) 0);
            Arrays.fill(terrainTickingBlockSectionCounts, (short) 0);
            Arrays.fill(terrainTickingFluidSectionCounts, (short) 0);
            Arrays.fill(terrainLightEmissionSectionCounts, 0);
        }
        clearTerrainSectionCounterKnownWords();
    }

    private void clearTerrainSectionCounterKnownWords() {
        Arrays.fill(terrainSectionCounterKnownWords, 0L);
    }

    private void ensureTerrainSectionCountersKnown(int sectionIndex) {
        if (hasKnownTerrainSectionCounts(sectionIndex)) {
            return;
        }
        ensureTerrainSectionCounterCapacity();
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        int nonEmpty = 0;
        int tickingBlocks = 0;
        int tickingFluids = 0;
        int lightEmission = 0;
        for (int y = minY; y < maxY; y++) {
            int baseIndex = (y - minBuildHeight) << 8;
            for (int column = 0; column < COLUMN_COUNT; column++) {
                int blockId = blockIds[baseIndex | column];
                if (!FastBlockStateCache.isEmpty(blockId)) {
                    nonEmpty++;
                    if (FastBlockStateCache.isRandomlyTickingBlock(blockId)) {
                        tickingBlocks++;
                    }
                }
                if (!FastBlockStateCache.isFluidEmpty(blockId)
                        && FastBlockStateCache.isRandomlyTickingFluid(blockId)) {
                    tickingFluids++;
                }
                if (FastBlockStateCache.hasLightEmission(blockId)) {
                    lightEmission++;
                }
            }
        }
        setTerrainSectionCounts(sectionIndex, nonEmpty, tickingBlocks, tickingFluids, lightEmission);
    }

    private void setTerrainSectionCounts(
            int sectionIndex,
            int nonEmpty,
            int tickingBlocks,
            int tickingFluids,
            int lightEmission
    ) {
        ensureTerrainSectionCounterCapacity();
        terrainNonEmptySectionCounts[sectionIndex] = (short) nonEmpty;
        terrainTickingBlockSectionCounts[sectionIndex] = (short) tickingBlocks;
        terrainTickingFluidSectionCounts[sectionIndex] = (short) tickingFluids;
        terrainLightEmissionSectionCounts[sectionIndex] = lightEmission;
        int word = sectionIndex >>> 6;
        ensureTerrainSectionCounterWordCapacity(word + 1);
        terrainSectionCounterKnownWords[word] |= 1L << (sectionIndex & 63);
    }

    private void incrementTerrainSectionCountsFromAir(int sectionIndex, int newId) {
        if (!hasKnownTerrainSectionCounts(sectionIndex)) {
            return;
        }
        int nonEmpty = terrainNonEmptySectionCounts[sectionIndex] & 0xFFFF;
        int tickingBlocks = terrainTickingBlockSectionCounts[sectionIndex] & 0xFFFF;
        int tickingFluids = terrainTickingFluidSectionCounts[sectionIndex] & 0xFFFF;
        int lightEmission = terrainLightEmissionSectionCounts[sectionIndex];

        if (!FastBlockStateCache.isEmpty(newId)) {
            nonEmpty++;
            if (FastBlockStateCache.isRandomlyTickingBlock(newId)) {
                tickingBlocks++;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(newId) && FastBlockStateCache.isRandomlyTickingFluid(newId)) {
            tickingFluids++;
        }
        if (FastBlockStateCache.hasLightEmission(newId)) {
            lightEmission++;
        }

        terrainNonEmptySectionCounts[sectionIndex] = (short) nonEmpty;
        terrainTickingBlockSectionCounts[sectionIndex] = (short) tickingBlocks;
        terrainTickingFluidSectionCounts[sectionIndex] = (short) tickingFluids;
        terrainLightEmissionSectionCounts[sectionIndex] = lightEmission;
    }

    private void updateTerrainSectionCounts(int sectionIndex, int oldId, int newId) {
        if (oldId == newId || !hasKnownTerrainSectionCounts(sectionIndex)) {
            return;
        }
        int nonEmpty = terrainNonEmptySectionCounts[sectionIndex] & 0xFFFF;
        int tickingBlocks = terrainTickingBlockSectionCounts[sectionIndex] & 0xFFFF;
        int tickingFluids = terrainTickingFluidSectionCounts[sectionIndex] & 0xFFFF;
        int lightEmission = terrainLightEmissionSectionCounts[sectionIndex];

        if (!FastBlockStateCache.isEmpty(oldId)) {
            nonEmpty--;
            if (FastBlockStateCache.isRandomlyTickingBlock(oldId)) {
                tickingBlocks--;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(oldId) && FastBlockStateCache.isRandomlyTickingFluid(oldId)) {
            tickingFluids--;
        }
        if (FastBlockStateCache.hasLightEmission(oldId)) {
            lightEmission--;
        }
        if (!FastBlockStateCache.isEmpty(newId)) {
            nonEmpty++;
            if (FastBlockStateCache.isRandomlyTickingBlock(newId)) {
                tickingBlocks++;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(newId) && FastBlockStateCache.isRandomlyTickingFluid(newId)) {
            tickingFluids++;
        }
        if (FastBlockStateCache.hasLightEmission(newId)) {
            lightEmission++;
        }

        terrainNonEmptySectionCounts[sectionIndex] = (short) Math.max(0, nonEmpty);
        terrainTickingBlockSectionCounts[sectionIndex] = (short) Math.max(0, tickingBlocks);
        terrainTickingFluidSectionCounts[sectionIndex] = (short) Math.max(0, tickingFluids);
        terrainLightEmissionSectionCounts[sectionIndex] = Math.max(0, lightEmission);
    }

    private void requireBlockBuffer() {
        if (!blockBufferEnabled || blockIds == null) {
            throw new IllegalStateException("block buffer is not allocated");
        }
    }

    private void requireDensityBuffer() {
        if (!densityBufferEnabled || densityBuffer == null) {
            throw new IllegalStateException("density buffer is not allocated");
        }
    }

    private void requireAquiferBuffer() {
        if (!aquiferBufferEnabled || aquiferBlockIds == null) {
            throw new IllegalStateException("aquifer buffer is not allocated");
        }
    }

    private void requireBiomeBuffer() {
        if (!biomeBufferEnabled || biomeIds == null) {
            throw new IllegalStateException("biome buffer is not allocated");
        }
    }

    private void requireSurfaceBuffer() {
        if (!surfaceBufferEnabled || surfaceBlockIds == null) {
            throw new IllegalStateException("surface buffer is not allocated");
        }
    }

    private void requireCarverMask() {
        if (!carverMaskEnabled) {
            throw new IllegalStateException("carver mask is not allocated");
        }
    }

    private void requireImported() {
        if (!imported) {
            throw new IllegalStateException("workspace metadata is not imported");
        }
    }

    private void setBlockIdRaw(int index, int blockId) {
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative");
        }
        materializeLazyAirSectionForWorkspaceIndex(index);
        blockIds[index] = blockId;
    }

    private void clearDirtySections() {
        Arrays.fill(dirtySectionWords, 0L);
    }

    private void clearDirtySection(int sectionIndex) {
        int word = sectionIndex >>> 6;
        if (word < dirtySectionWords.length) {
            dirtySectionWords[word] &= ~(1L << sectionIndex);
        }
    }

    private void markTerrainSectionOnlyDirty(int sectionIndex) {
        int word = sectionIndex >>> 6;
        ensureDirtySectionWordCapacity(word + 1);
        terrainSectionOnlyDirtyWords[word] |= 1L << (sectionIndex & 63);
    }

    private void clearTerrainSectionOnlyDirties() {
        Arrays.fill(terrainSectionOnlyDirtyWords, 0L);
    }

    private void clearTerrainSectionOnlyDirty(int sectionIndex) {
        int word = sectionIndex >>> 6;
        if (word < terrainSectionOnlyDirtyWords.length) {
            terrainSectionOnlyDirtyWords[word] &= ~(1L << (sectionIndex & 63));
        }
    }

    private void markDirtyBlockIndex(int index) {
        int word = index >>> 6;
        ensureDirtyBlockWordCapacity(word + 1);
        dirtyBlockWords[word] |= 1L << index;
    }

    private void markDirtyBlockColumnIndex(int index) {
        dirtyBlockColumnWords[index >>> 6] |= 1L << index;
    }

    private void markDirtyHeightColumnIndex(int index) {
        dirtyHeightColumnWords[index >>> 6] |= 1L << index;
    }

    private void markDirtySurfaceColumnIndex(int index) {
        dirtySurfaceColumnWords[index >>> 6] |= 1L << index;
    }

    private void markDirtyLightColumnIndex(int index) {
        dirtyLightColumnWords[index >>> 6] |= 1L << index;
    }

    private boolean isDirtyBlockIndex(int index) {
        int word = index >>> 6;
        return word < dirtyBlockWords.length && (dirtyBlockWords[word] & (1L << index)) != 0L;
    }

    private void clearDirtyBlocks() {
        Arrays.fill(dirtyBlockWords, 0L);
    }

    private void clearDirtyBlockSection(int sectionIndex) {
        int sectionY = minSectionY + sectionIndex;
        int sectionMinY = sectionY * CHUNK_WIDTH;
        int minY = Math.max(minBuildHeight, sectionMinY);
        int maxY = Math.min(minBuildHeight + buildHeight, sectionMinY + CHUNK_WIDTH);
        if (minY >= maxY) {
            return;
        }
        int firstIndex = (minY - minBuildHeight) << 8;
        int lastIndexExclusive = ((maxY - minBuildHeight) << 8);
        int firstWord = firstIndex >>> 6;
        int lastWord = (lastIndexExclusive - 1) >>> 6;
        for (int word = firstWord; word <= lastWord && word < dirtyBlockWords.length; word++) {
            long mask = -1L;
            if (word == firstWord) {
                mask &= -1L << firstIndex;
            }
            if (word == lastWord && (lastIndexExclusive & 63) != 0) {
                mask &= (1L << (lastIndexExclusive & 63)) - 1L;
            }
            dirtyBlockWords[word] &= ~mask;
        }
    }

    public void clearCarverMask() {
        Arrays.fill(carverMaskWords, 0L);
    }

    private void clearDirtyColumns() {
        Arrays.fill(dirtyColumnWords, 0L);
        clearDirtyBlockColumns();
        clearDirtyHeightColumns();
        clearDirtySurfaceColumns();
        clearDirtyLightColumns();
    }

    private void clearDirtyBlockColumns() {
        Arrays.fill(dirtyBlockColumnWords, 0L);
        Arrays.fill(dirtyColumnWords, 0L);
    }

    private void clearDirtyHeightColumns() {
        Arrays.fill(dirtyHeightColumnWords, 0L);
        Arrays.fill(dirtyColumnWords, 0L);
    }

    private void clearDirtySurfaceColumns() {
        Arrays.fill(dirtySurfaceColumnWords, 0L);
        Arrays.fill(dirtyColumnWords, 0L);
    }

    public void clearDirtyLightColumns() {
        Arrays.fill(dirtyLightColumnWords, 0L);
        Arrays.fill(dirtyColumnWords, 0L);
    }

    private void mergeDirtyColumnWords() {
        for (int i = 0; i < dirtyColumnWords.length; i++) {
            dirtyColumnWords[i] = dirtyBlockColumnWords[i] | dirtyHeightColumnWords[i]
                    | dirtySurfaceColumnWords[i] | dirtyLightColumnWords[i];
        }
    }

    private static long retainedIntBytes(int[] values) {
        return values == null ? 0L : 16L + (long) values.length * Integer.BYTES;
    }

    private static long retainedShortBytes(short[] values) {
        return values == null ? 0L : 16L + (long) values.length * Short.BYTES;
    }

    private static long retainedLongBytes(long[] values) {
        return values == null ? 0L : 16L + (long) values.length * Long.BYTES;
    }

    private static long retainedDoubleBytes(double[] values) {
        return values == null ? 0L : 16L + (long) values.length * Double.BYTES;
    }

    @FunctionalInterface
    public interface BlockIdReader {
        int blockId(int localX, int y, int localZ);
    }

    @FunctionalInterface
    public interface BlockIdWriter {
        void writeBlockId(int localX, int y, int localZ, int blockId);
    }

    @FunctionalInterface
    public interface DirtyBlockRunWriter {
        void writeRun(int sectionLocalIndex, int workspaceIndex, int length);
    }
}
