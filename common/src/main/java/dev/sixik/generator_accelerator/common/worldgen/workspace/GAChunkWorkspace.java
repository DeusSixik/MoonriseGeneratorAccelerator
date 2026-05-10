package dev.sixik.generator_accelerator.common.worldgen.workspace;

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
    private int[] heightCandidates = new int[COLUMN_COUNT];
    private long[] dirtySectionWords = new long[1];
    private final long[] dirtyColumnWords = new long[4];

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
        blockBufferEnabled = true;
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
        blockIds[index] = blockId;
        markDirtyBlock(localX, y, localZ);
    }

    public int blockId(int localX, int y, int localZ) {
        requireBlockBuffer();
        int index = blockIndex(localX, y, localZ);
        if (index >= blockCapacity) {
            throw new IndexOutOfBoundsException("block buffer is not sized for y=" + y);
        }
        return blockIds[index];
    }

    public void setHeightCandidate(int localX, int localZ, int y) {
        heightCandidates[columnIndex(localX, localZ)] = y;
        markDirtyColumn(localX, localZ);
    }

    public int heightCandidate(int localX, int localZ) {
        return heightCandidates[columnIndex(localX, localZ)];
    }

    public void markDirtyBlock(int localX, int y, int localZ) {
        markDirtyColumn(localX, localZ);
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
        int index = columnIndex(localX, localZ);
        dirtyColumnWords[index >>> 6] |= 1L << index;
    }

    public boolean isDirtySection(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            return false;
        }
        int word = sectionIndex >>> 6;
        return word < dirtySectionWords.length && (dirtySectionWords[word] & (1L << sectionIndex)) != 0L;
    }

    public boolean isDirtyColumn(int localX, int localZ) {
        int index = columnIndex(localX, localZ);
        return (dirtyColumnWords[index >>> 6] & (1L << index)) != 0L;
    }

    public long estimatedRetainedBytes() {
        long bytes = 16L; // object/header approximation anchor
        bytes += retainedIntBytes(blockIds);
        bytes += retainedIntBytes(heightCandidates);
        bytes += retainedLongBytes(dirtySectionWords);
        bytes += retainedLongBytes(dirtyColumnWords);
        return bytes;
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

    public int[] blockIds() {
        return blockIds;
    }

    public int[] heightCandidates() {
        return heightCandidates;
    }

    public long[] dirtySectionWords() {
        return dirtySectionWords;
    }

    public long[] dirtyColumnWords() {
        return dirtyColumnWords;
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
    }

    private void resetForBegin() {
        clearRuntimeState();
        clearMetadata();
        metrics.clear();
    }

    private void clearRuntimeState() {
        blockBufferEnabled = false;
        blockCapacity = 0;
        Arrays.fill(heightCandidates, UNKNOWN_HEIGHT);
        Arrays.fill(dirtySectionWords, 0L);
        Arrays.fill(dirtyColumnWords, 0L);
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
        if (heightCandidates.length > maxRetainedHeightInts) {
            heightCandidates = new int[COLUMN_COUNT];
            Arrays.fill(heightCandidates, UNKNOWN_HEIGHT);
        }
        if (dirtySectionWords.length > maxRetainedDirtyWords) {
            dirtySectionWords = new long[Math.max(1, maxRetainedDirtyWords)];
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
    }

    private void requireBlockBuffer() {
        if (!blockBufferEnabled || blockIds == null) {
            throw new IllegalStateException("block buffer is not allocated");
        }
    }

    private static long retainedIntBytes(int[] values) {
        return values == null ? 0L : 16L + (long) values.length * Integer.BYTES;
    }

    private static long retainedLongBytes(long[] values) {
        return values == null ? 0L : 16L + (long) values.length * Long.BYTES;
    }
}
