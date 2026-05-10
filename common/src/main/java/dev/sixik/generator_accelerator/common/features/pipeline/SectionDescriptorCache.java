package dev.sixik.generator_accelerator.common.features.pipeline;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

public final class SectionDescriptorCache {
    private static final int INITIAL_DESCRIPTOR_CAPACITY = 32;
    private static final int MAX_RETAINED_DESCRIPTOR_CAPACITY = 256;
    private static final int MAX_EXCESSIVE_DESCRIPTOR_CAPACITY = 1_024;
    private static final int MAX_RETAINED_HEIGHT_CACHE_CAPACITY = 64;
    private static final int MAX_EXCESSIVE_HEIGHT_CACHE_CAPACITY = 256;
    private static final int MAX_RETAINED_HEIGHT_SCAN_CAPACITY = 128;
    private static final int MAX_EXCESSIVE_HEIGHT_SCAN_CAPACITY = 512;
    private static final int OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD = 4;
    private static final short[] EMPTY_HEIGHTS = new short[SectionDescriptor.COLUMN_COUNT];
    private static final short NO_TOP_WATER = Short.MIN_VALUE;
    private static final int UNKNOWN_PALETTE_FLAGS = SectionDescriptor.PALETTE_AIR
            | SectionDescriptor.PALETTE_WATER
            | SectionDescriptor.PALETTE_LAVA
            | SectionDescriptor.PALETTE_SOLID;
    private static final int UNKNOWN_BLOCK_CLASS_FLAGS = SectionDescriptor.CLASS_STONE_LIKE
            | SectionDescriptor.CLASS_DIRT_LIKE
            | SectionDescriptor.CLASS_REPLACEABLE
            | SectionDescriptor.CLASS_ORE_TARGET
            | SectionDescriptor.CLASS_SURFACE_CANDIDATE
            | SectionDescriptor.CLASS_TREE_SOIL;

    private ChunkAccess[] chunks = new ChunkAccess[INITIAL_DESCRIPTOR_CAPACITY];
    private long[] keys = new long[INITIAL_DESCRIPTOR_CAPACITY];
    private SectionDescriptor[] descriptors = new SectionDescriptor[INITIAL_DESCRIPTOR_CAPACITY];
    private final Long2IntOpenHashMap indexByKey = new Long2IntOpenHashMap(INITIAL_DESCRIPTOR_CAPACITY);
    private ChunkAccess[] heightChunks = new ChunkAccess[16];
    private long[] heightChunkKeys = new long[16];
    private short[][] worldSurfaceHeights = new short[16][];
    private short[][] oceanFloorHeights = new short[16][];
    private short[][] motionBlockingHeights = new short[16][];
    private short[][] topWaterHeights = new short[16][];
    private int[][] chunkColumnPaletteFlags = new int[16][];
    private int[][] chunkColumnBlockClassFlags = new int[16][];
    private final Long2IntOpenHashMap heightIndexByChunkKey = new Long2IntOpenHashMap(16);
    private SectionDescriptor[] heightScanDescriptors = new SectionDescriptor[32];
    private int size;
    private int heightEntryCount;
    private int lastSectionX = Integer.MIN_VALUE;
    private int lastSectionY = Integer.MIN_VALUE;
    private int lastSectionZ = Integer.MIN_VALUE;
    private int lastIndex = -1;
    private ChunkAccess lazyChunk;
    private int lazyChunkX;
    private int lazyChunkZ;
    private int oversizedDescriptorClearCount;
    private int oversizedHeightCacheClearCount;
    private int oversizedHeightScanClearCount;

    public SectionDescriptorCache() {
        this.indexByKey.defaultReturnValue(-1);
        this.heightIndexByChunkKey.defaultReturnValue(-1);
        for (int i = 0; i < this.descriptors.length; i++) {
            this.descriptors[i] = new SectionDescriptor();
        }
    }

    public void clear() {
        for (int i = 0; i < this.size; i++) {
            this.chunks[i] = null;
            this.descriptors[i].clear();
        }
        Arrays.fill(this.heightChunks, 0, this.heightEntryCount, null);
        this.size = 0;
        this.heightEntryCount = 0;
        this.lastSectionX = Integer.MIN_VALUE;
        this.lastSectionY = Integer.MIN_VALUE;
        this.lastSectionZ = Integer.MIN_VALUE;
        this.lastIndex = -1;
        this.lazyChunk = null;
        this.lazyChunkX = 0;
        this.lazyChunkZ = 0;
        this.indexByKey.clear();
        this.heightIndexByChunkKey.clear();
        this.shrinkOversizedBuffers();
    }

    public int size() {
        return this.size;
    }

    public SectionDescriptor descriptorAt(int index) {
        return this.descriptors[index];
    }

    public SectionDescriptor findByBlockPos(int x, int y, int z) {
        return this.findBySectionPos(x >> 4, y >> 4, z >> 4);
    }

    public SectionDescriptor findBySectionPos(int sectionX, int sectionY, int sectionZ) {
        int cachedIndex = this.lastIndex;
        if (cachedIndex >= 0
                && this.lastSectionX == sectionX
                && this.lastSectionY == sectionY
                && this.lastSectionZ == sectionZ) {
            return this.descriptors[cachedIndex];
        }

        int index = this.indexByKey.get(key(sectionX, sectionZ, sectionY));
        if (index < 0) {
            ChunkAccess chunk = this.lazyChunk;
            if (chunk == null || this.lazyChunkX != sectionX || this.lazyChunkZ != sectionZ) {
                return null;
            }
            long start = DecorationPipelineMetrics.startTimer();
            SectionDescriptor descriptor = this.getOrBuild(chunk, sectionY);
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_DESCRIPTOR_NANOS, start);
            return descriptor;
        }

        this.lastSectionX = sectionX;
        this.lastSectionY = sectionY;
        this.lastSectionZ = sectionZ;
        this.lastIndex = index;
        return this.descriptors[index];
    }

    public SectionDescriptor getOrBuild(ChunkAccess chunk, int sectionY) {
        long key = key(chunk.getPos(), sectionY);
        int index = this.indexByKey.get(key);
        if (index >= 0) {
            if (this.chunks[index] == chunk) {
                return this.descriptors[index];
            }
            return this.rebuildAt(index, chunk, sectionY, key);
        }
        return this.buildNew(chunk, sectionY, key);
    }

    public SectionDescriptor getOrBuildForBlockY(ChunkAccess chunk, int blockY) {
        return this.getOrBuild(chunk, blockY >> 4);
    }

    public void buildChunk(ChunkAccess chunk) {
        // Height prefill builds all section descriptors; avoid a duplicate getOrBuild pass here.
        this.ensureHeightEntry(chunk);
    }

    public void prepareChunkLazy(ChunkAccess chunk) {
        this.lazyChunk = chunk;
        ChunkPos pos = chunk.getPos();
        this.lazyChunkX = pos.x;
        this.lazyChunkZ = pos.z;
    }

    public void noteBlockMutation(ChunkAccess chunk, int blockX, int blockY, int blockZ) {
        int sectionY = blockY >> 4;
        ChunkPos pos = chunk.getPos();
        long sectionKey = key(pos, sectionY);
        int index = this.indexByKey.get(sectionKey);
        if (index >= 0 && this.chunks[index] == chunk) {
            this.descriptors[index].rebuildColumn(blockX & 15, blockZ & 15);
        } else if (index >= 0) {
            this.rebuildAt(index, chunk, sectionY, sectionKey);
        } else {
            long chunkKey = pos.toLong();
            if (this.heightEntryCount == 0 || this.heightIndexByChunkKey.get(chunkKey) < 0) {
                return;
            }
        }
        this.updateHeightCachesAfterMutation(chunk, blockX & 15, blockZ & 15);
    }

    public int firstAvailableHeight(ChunkAccess chunk, Heightmap.Types type, int localX, int localZ) {
        short[] heights = this.heightArray(chunk, type);
        if (heights == EMPTY_HEIGHTS) {
            return Integer.MIN_VALUE;
        }
        return heights[(localZ << 4) | localX];
    }

    public int firstAvailableHeight(int chunkX, int chunkZ, Heightmap.Types type, int localX, int localZ) {
        short[] heights = this.heightArray(chunkX, chunkZ, type);
        if (heights == EMPTY_HEIGHTS) {
            return Integer.MIN_VALUE;
        }
        return heights[(localZ << 4) | localX];
    }

    public int topWaterHeight(ChunkAccess chunk, int localX, int localZ) {
        short[] heights = this.topWaterArray(chunk);
        if (heights == EMPTY_HEIGHTS) {
            return Integer.MIN_VALUE;
        }
        short height = heights[(localZ << 4) | localX];
        return height == NO_TOP_WATER ? Integer.MIN_VALUE : height;
    }

    public int topWaterHeight(int chunkX, int chunkZ, int localX, int localZ) {
        short[] heights = this.topWaterArray(chunkX, chunkZ);
        if (heights == EMPTY_HEIGHTS) {
            return Integer.MIN_VALUE;
        }
        short height = heights[(localZ << 4) | localX];
        return height == NO_TOP_WATER ? Integer.MIN_VALUE : height;
    }

    public int chunkColumnPaletteFlags(int chunkX, int chunkZ, int localX, int localZ) {
        int[] flags = this.paletteFlagArray(chunkX, chunkZ);
        if (flags == null) {
            return this.isLazyChunk(chunkX, chunkZ) ? UNKNOWN_PALETTE_FLAGS : 0;
        }
        return flags[(localZ << 4) | localX];
    }

    public int chunkColumnBlockClassFlags(int chunkX, int chunkZ, int localX, int localZ) {
        int[] flags = this.blockClassFlagArray(chunkX, chunkZ);
        if (flags == null) {
            return this.isLazyChunk(chunkX, chunkZ) ? UNKNOWN_BLOCK_CLASS_FLAGS : 0;
        }
        return flags[(localZ << 4) | localX];
    }

    private SectionDescriptor buildNew(ChunkAccess chunk, int sectionY, long key) {
        if (this.size == this.descriptors.length) {
            this.grow();
        }

        int index = this.size++;
        this.keys[index] = key;
        this.chunks[index] = chunk;
        this.indexByKey.put(key, index);

        LevelChunkSection[] sections = chunk.getSections();
        int sectionIndex = sectionY - chunk.getMinSection();
        LevelChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
        ChunkPos pos = chunk.getPos();
        SectionDescriptor descriptor = this.descriptors[index];
        descriptor.build(chunk, section, pos.x, sectionY, pos.z);
        this.touchLast(pos.x, sectionY, pos.z, index);
        return descriptor;
    }

    private SectionDescriptor rebuildAt(int index, ChunkAccess chunk, int sectionY, long key) {
        this.keys[index] = key;
        this.chunks[index] = chunk;
        this.indexByKey.put(key, index);

        LevelChunkSection[] sections = chunk.getSections();
        int sectionIndex = sectionY - chunk.getMinSection();
        LevelChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
        ChunkPos pos = chunk.getPos();
        SectionDescriptor descriptor = this.descriptors[index];
        descriptor.build(chunk, section, pos.x, sectionY, pos.z);
        this.touchLast(pos.x, sectionY, pos.z, index);
        return descriptor;
    }

    private void grow() {
        int oldLength = this.descriptors.length;
        int newLength = oldLength << 1;

        ChunkAccess[] newChunks = new ChunkAccess[newLength];
        long[] newKeys = new long[newLength];
        SectionDescriptor[] newDescriptors = new SectionDescriptor[newLength];

        System.arraycopy(this.chunks, 0, newChunks, 0, oldLength);
        System.arraycopy(this.keys, 0, newKeys, 0, oldLength);
        System.arraycopy(this.descriptors, 0, newDescriptors, 0, oldLength);
        for (int i = oldLength; i < newLength; i++) {
            newDescriptors[i] = new SectionDescriptor();
        }

        this.chunks = newChunks;
        this.keys = newKeys;
        this.descriptors = newDescriptors;
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    private short[] heightArray(ChunkAccess chunk, Heightmap.Types type) {
        int index = this.ensureHeightEntry(chunk);
        return switch (type) {
            case WORLD_SURFACE, WORLD_SURFACE_WG -> this.worldSurfaceHeights[index];
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> this.oceanFloorHeights[index];
            case MOTION_BLOCKING -> this.motionBlockingHeights[index];
            default -> EMPTY_HEIGHTS;
        };
    }

    private short[] heightArray(int chunkX, int chunkZ, Heightmap.Types type) {
        if (this.heightEntryCount == 0) {
            return EMPTY_HEIGHTS;
        }
        int index = this.heightIndexByChunkKey.get(ChunkPos.asLong(chunkX, chunkZ));
        if (index < 0) {
            return EMPTY_HEIGHTS;
        }
        return switch (type) {
            case WORLD_SURFACE, WORLD_SURFACE_WG -> this.worldSurfaceHeights[index];
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> this.oceanFloorHeights[index];
            case MOTION_BLOCKING -> this.motionBlockingHeights[index];
            default -> EMPTY_HEIGHTS;
        };
    }

    private int ensureHeightEntry(ChunkAccess chunk) {
        long chunkKey = chunk.getPos().toLong();
        int index = this.heightIndexByChunkKey.get(chunkKey);
        if (index >= 0) {
            if (this.heightChunks[index] == chunk) {
                return index;
            }
            return this.rebuildHeightEntry(index, chunk, chunkKey);
        }
        return this.buildHeightEntry(chunk, chunkKey);
    }

    private int buildHeightEntry(ChunkAccess chunk, long chunkKey) {
        if (this.heightEntryCount == this.heightChunks.length) {
            this.growHeightCache();
        }

        int index = this.heightEntryCount++;
        this.heightChunkKeys[index] = chunkKey;
        this.heightChunks[index] = chunk;
        this.heightIndexByChunkKey.put(chunkKey, index);
        this.ensureHeightArrays(index);
        this.fillHeights(index, chunk);
        return index;
    }

    private int rebuildHeightEntry(int index, ChunkAccess chunk, long chunkKey) {
        this.heightChunkKeys[index] = chunkKey;
        this.heightChunks[index] = chunk;
        this.heightIndexByChunkKey.put(chunkKey, index);
        this.ensureHeightArrays(index);
        this.fillHeights(index, chunk);
        return index;
    }

    private void ensureHeightArrays(int index) {
        if (this.worldSurfaceHeights[index] == null) {
            this.worldSurfaceHeights[index] = new short[SectionDescriptor.COLUMN_COUNT];
            this.oceanFloorHeights[index] = new short[SectionDescriptor.COLUMN_COUNT];
            this.motionBlockingHeights[index] = new short[SectionDescriptor.COLUMN_COUNT];
            this.topWaterHeights[index] = new short[SectionDescriptor.COLUMN_COUNT];
            this.chunkColumnPaletteFlags[index] = new int[SectionDescriptor.COLUMN_COUNT];
            this.chunkColumnBlockClassFlags[index] = new int[SectionDescriptor.COLUMN_COUNT];
        }
    }

    private void fillHeights(int index, ChunkAccess chunk) {
        short[] worldSurface = this.worldSurfaceHeights[index];
        short[] oceanFloor = this.oceanFloorHeights[index];
        short[] motionBlocking = this.motionBlockingHeights[index];
        short[] topWater = this.topWaterHeights[index];
        int[] paletteFlags = this.chunkColumnPaletteFlags[index];
        int[] blockClassFlags = this.chunkColumnBlockClassFlags[index];
        int minBuildHeight = chunk.getMinBuildHeight();
        short minHeight = (short) minBuildHeight;
        Arrays.fill(worldSurface, minHeight);
        Arrays.fill(oceanFloor, minHeight);
        Arrays.fill(motionBlocking, minHeight);
        Arrays.fill(topWater, NO_TOP_WATER);
        Arrays.fill(paletteFlags, 0);
        Arrays.fill(blockClassFlags, 0);

        int minSection = chunk.getMinSection();
        int sectionCount = chunk.getSections().length;
        SectionDescriptor[] sectionDescriptors = this.sectionDescriptorsFor(chunk, minSection, sectionCount);
        for (int localZ = 0; localZ < SectionDescriptor.SECTION_EDGE; localZ++) {
            for (int localX = 0; localX < SectionDescriptor.SECTION_EDGE; localX++) {
                int columnIndex = (localZ << 4) | localX;
                boolean foundWorldSurface = false;
                boolean foundOceanFloor = false;
                boolean foundMotionBlocking = false;
                boolean foundTopWater = false;
                for (int sectionIndex = sectionCount - 1; sectionIndex >= 0; sectionIndex--) {
                    SectionDescriptor descriptor = sectionDescriptors[sectionIndex];
                    paletteFlags[columnIndex] |= descriptor.columnPaletteFlags(localX, localZ);
                    blockClassFlags[columnIndex] |= descriptor.columnBlockClassFlags(localX, localZ);
                    if (!foundWorldSurface) {
                        int worldSurfaceY = descriptor.columnHighestFilledBlockY(localX, localZ);
                        if (worldSurfaceY != Integer.MIN_VALUE) {
                            worldSurface[columnIndex] = (short) (worldSurfaceY + 1);
                            foundWorldSurface = true;
                        }
                    }
                    if (!foundOceanFloor) {
                        int oceanFloorY = descriptor.columnHighestSolidBlockY(localX, localZ);
                        if (oceanFloorY != Integer.MIN_VALUE) {
                            oceanFloor[columnIndex] = (short) (oceanFloorY + 1);
                            foundOceanFloor = true;
                        }
                    }
                    if (!foundMotionBlocking) {
                        int motionBlockingY = descriptor.columnHighestMotionBlockingBlockY(localX, localZ);
                        if (motionBlockingY != Integer.MIN_VALUE) {
                            motionBlocking[columnIndex] = (short) (motionBlockingY + 1);
                            foundMotionBlocking = true;
                        }
                    }
                    if (!foundTopWater) {
                        int topWaterY = descriptor.columnHighestWaterBlockY(localX, localZ);
                        if (topWaterY != Integer.MIN_VALUE) {
                            topWater[columnIndex] = (short) topWaterY;
                            foundTopWater = true;
                        }
                    }
                    if (foundWorldSurface && foundOceanFloor && foundMotionBlocking && foundTopWater) {
                        break;
                    }
                }
            }
        }
    }

    private void updateHeightCachesAfterMutation(ChunkAccess chunk, int localX, int localZ) {
        long chunkKey = chunk.getPos().toLong();
        int index = this.heightIndexByChunkKey.get(chunkKey);
        if (index < 0) {
            return;
        }
        if (this.heightChunks[index] != chunk) {
            index = this.rebuildHeightEntry(index, chunk, chunkKey);
        }
        this.recomputeHeightColumn(index, chunk, localX, localZ);
    }

    private void recomputeHeightColumn(int index, ChunkAccess chunk, int localX, int localZ) {
        short[] worldSurface = this.worldSurfaceHeights[index];
        short[] oceanFloor = this.oceanFloorHeights[index];
        short[] motionBlocking = this.motionBlockingHeights[index];
        short[] topWater = this.topWaterHeights[index];
        int[] paletteFlags = this.chunkColumnPaletteFlags[index];
        int[] blockClassFlags = this.chunkColumnBlockClassFlags[index];
        int columnIndex = (localZ << 4) | localX;
        short minHeight = (short) chunk.getMinBuildHeight();
        worldSurface[columnIndex] = minHeight;
        oceanFloor[columnIndex] = minHeight;
        motionBlocking[columnIndex] = minHeight;
        topWater[columnIndex] = NO_TOP_WATER;
        paletteFlags[columnIndex] = 0;
        blockClassFlags[columnIndex] = 0;

        int minSection = chunk.getMinSection();
        int sectionCount = chunk.getSections().length;
        SectionDescriptor[] sectionDescriptors = this.sectionDescriptorsFor(chunk, minSection, sectionCount);
        boolean foundWorldSurface = false;
        boolean foundOceanFloor = false;
        boolean foundMotionBlocking = false;
        boolean foundTopWater = false;
        for (int sectionIndex = sectionCount - 1; sectionIndex >= 0; sectionIndex--) {
            SectionDescriptor descriptor = sectionDescriptors[sectionIndex];
            paletteFlags[columnIndex] |= descriptor.columnPaletteFlags(localX, localZ);
            blockClassFlags[columnIndex] |= descriptor.columnBlockClassFlags(localX, localZ);
            if (!foundWorldSurface) {
                int worldSurfaceY = descriptor.columnHighestFilledBlockY(localX, localZ);
                if (worldSurfaceY != Integer.MIN_VALUE) {
                    worldSurface[columnIndex] = (short) (worldSurfaceY + 1);
                    foundWorldSurface = true;
                }
            }
            if (!foundOceanFloor) {
                int oceanFloorY = descriptor.columnHighestSolidBlockY(localX, localZ);
                if (oceanFloorY != Integer.MIN_VALUE) {
                    oceanFloor[columnIndex] = (short) (oceanFloorY + 1);
                    foundOceanFloor = true;
                }
            }
            if (!foundMotionBlocking) {
                int motionBlockingY = descriptor.columnHighestMotionBlockingBlockY(localX, localZ);
                if (motionBlockingY != Integer.MIN_VALUE) {
                    motionBlocking[columnIndex] = (short) (motionBlockingY + 1);
                    foundMotionBlocking = true;
                }
            }
            if (!foundTopWater) {
                int topWaterY = descriptor.columnHighestWaterBlockY(localX, localZ);
                if (topWaterY != Integer.MIN_VALUE) {
                    topWater[columnIndex] = (short) topWaterY;
                    foundTopWater = true;
                }
            }
            if (foundWorldSurface && foundOceanFloor && foundMotionBlocking && foundTopWater) {
                return;
            }
        }
    }

    private SectionDescriptor[] sectionDescriptorsFor(ChunkAccess chunk, int minSection, int sectionCount) {
        if (this.heightScanDescriptors.length < sectionCount) {
            this.heightScanDescriptors = new SectionDescriptor[sectionCount];
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            this.heightScanDescriptors[sectionIndex] = this.getOrBuild(chunk, minSection + sectionIndex);
        }
        return this.heightScanDescriptors;
    }

    private void growHeightCache() {
        int oldLength = this.heightChunks.length;
        int newLength = oldLength << 1;
        this.heightChunks = grow(this.heightChunks, newLength);
        this.heightChunkKeys = grow(this.heightChunkKeys, newLength);
        this.worldSurfaceHeights = grow(this.worldSurfaceHeights, newLength);
        this.oceanFloorHeights = grow(this.oceanFloorHeights, newLength);
        this.motionBlockingHeights = grow(this.motionBlockingHeights, newLength);
        this.topWaterHeights = grow(this.topWaterHeights, newLength);
        this.chunkColumnPaletteFlags = grow(this.chunkColumnPaletteFlags, newLength);
        this.chunkColumnBlockClassFlags = grow(this.chunkColumnBlockClassFlags, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    private short[] topWaterArray(ChunkAccess chunk) {
        int index = this.ensureHeightEntry(chunk);
        return this.topWaterHeights[index];
    }

    private short[] topWaterArray(int chunkX, int chunkZ) {
        if (this.heightEntryCount == 0) {
            return EMPTY_HEIGHTS;
        }
        int index = this.heightIndexByChunkKey.get(ChunkPos.asLong(chunkX, chunkZ));
        if (index < 0) {
            return EMPTY_HEIGHTS;
        }
        return this.topWaterHeights[index];
    }

    private int[] paletteFlagArray(int chunkX, int chunkZ) {
        if (this.heightEntryCount == 0) {
            return null;
        }
        int index = this.heightIndexByChunkKey.get(ChunkPos.asLong(chunkX, chunkZ));
        if (index < 0) {
            return null;
        }
        return this.chunkColumnPaletteFlags[index];
    }

    private int[] blockClassFlagArray(int chunkX, int chunkZ) {
        if (this.heightEntryCount == 0) {
            return null;
        }
        int index = this.heightIndexByChunkKey.get(ChunkPos.asLong(chunkX, chunkZ));
        if (index < 0) {
            return null;
        }
        return this.chunkColumnBlockClassFlags[index];
    }

    private boolean isLazyChunk(int chunkX, int chunkZ) {
        return this.lazyChunk != null && this.lazyChunkX == chunkX && this.lazyChunkZ == chunkZ;
    }

    private static long key(ChunkPos pos, int sectionY) {
        return key(pos.x, pos.z, sectionY);
    }

    private void touchLast(int sectionX, int sectionY, int sectionZ, int index) {
        this.lastSectionX = sectionX;
        this.lastSectionY = sectionY;
        this.lastSectionZ = sectionZ;
        this.lastIndex = index;
    }

    private static long key(int sectionX, int sectionZ, int sectionY) {
        return (((long) sectionX) & 0x3FFFFFFL) << 38 | (((long) sectionZ) & 0x3FFFFFFL) << 12 | ((long) sectionY & 0xFFFL);
    }

    private static ChunkAccess[] grow(ChunkAccess[] source, int newLength) {
        ChunkAccess[] copy = new ChunkAccess[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static long[] grow(long[] source, int newLength) {
        long[] copy = new long[newLength];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static short[][] grow(short[][] source, int newLength) {
        short[][] copy = new short[newLength][];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static int[][] grow(int[][] source, int newLength) {
        int[][] copy = new int[newLength][];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private void shrinkOversizedBuffers() {
        if (this.descriptors.length > MAX_EXCESSIVE_DESCRIPTOR_CAPACITY) {
            if (++this.oversizedDescriptorClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.chunks = new ChunkAccess[MAX_RETAINED_DESCRIPTOR_CAPACITY];
                this.keys = new long[MAX_RETAINED_DESCRIPTOR_CAPACITY];
                this.descriptors = new SectionDescriptor[MAX_RETAINED_DESCRIPTOR_CAPACITY];
                for (int i = 0; i < this.descriptors.length; i++) {
                    this.descriptors[i] = new SectionDescriptor();
                }
                this.oversizedDescriptorClearCount = 0;
            }
        } else {
            this.oversizedDescriptorClearCount = 0;
        }

        if (this.heightChunks.length > MAX_EXCESSIVE_HEIGHT_CACHE_CAPACITY) {
            if (++this.oversizedHeightCacheClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.heightChunks = new ChunkAccess[MAX_RETAINED_HEIGHT_CACHE_CAPACITY];
                this.heightChunkKeys = new long[MAX_RETAINED_HEIGHT_CACHE_CAPACITY];
                this.worldSurfaceHeights = new short[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.oceanFloorHeights = new short[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.motionBlockingHeights = new short[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.topWaterHeights = new short[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.chunkColumnPaletteFlags = new int[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.chunkColumnBlockClassFlags = new int[MAX_RETAINED_HEIGHT_CACHE_CAPACITY][];
                this.oversizedHeightCacheClearCount = 0;
            }
        } else {
            this.oversizedHeightCacheClearCount = 0;
        }

        if (this.heightScanDescriptors.length > MAX_EXCESSIVE_HEIGHT_SCAN_CAPACITY) {
            if (++this.oversizedHeightScanClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.heightScanDescriptors = new SectionDescriptor[MAX_RETAINED_HEIGHT_SCAN_CAPACITY];
                this.oversizedHeightScanClearCount = 0;
            }
        } else {
            this.oversizedHeightScanClearCount = 0;
        }
    }

    String debugSummary() {
        return "descriptorSize=" + this.size
                + '/' + this.descriptors.length
                + ",heightEntries=" + this.heightEntryCount
                + '/' + this.heightChunks.length
                + ",heightScanCap=" + this.heightScanDescriptors.length;
    }
}
