package dev.sixik.generator_accelerator.common.features.pipeline;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class SectionDescriptorCache {

    private ChunkAccess[] chunks = new ChunkAccess[16];
    private long[] keys = new long[16];
    private SectionDescriptor[] descriptors = new SectionDescriptor[16];
    private final Long2IntOpenHashMap indexByKey = new Long2IntOpenHashMap(16);
    private int size;
    private int lastSectionX = Integer.MIN_VALUE;
    private int lastSectionY = Integer.MIN_VALUE;
    private int lastSectionZ = Integer.MIN_VALUE;
    private int lastIndex = -1;

    public SectionDescriptorCache() {
        this.indexByKey.defaultReturnValue(-1);
        for (int i = 0; i < this.descriptors.length; i++) {
            this.descriptors[i] = new SectionDescriptor();
        }
    }

    public void clear() {
        for (int i = 0; i < this.size; i++) {
            this.chunks[i] = null;
            this.descriptors[i].clear();
        }
        this.size = 0;
        this.lastSectionX = Integer.MIN_VALUE;
        this.lastSectionY = Integer.MIN_VALUE;
        this.lastSectionZ = Integer.MIN_VALUE;
        this.lastIndex = -1;
        this.indexByKey.clear();
    }

    public int size() {
        return this.size;
    }

    public SectionDescriptor descriptorAt(int index) {
        return this.descriptors[index];
    }

    public SectionDescriptor findByBlockPos(int x, int y, int z) {
        int sectionX = x >> 4;
        int sectionY = y >> 4;
        int sectionZ = z >> 4;
        int cachedIndex = this.lastIndex;
        if (cachedIndex >= 0
                && this.lastSectionX == sectionX
                && this.lastSectionY == sectionY
                && this.lastSectionZ == sectionZ) {
            return this.descriptors[cachedIndex];
        }
        int index = this.indexByKey.get(key(sectionX, sectionZ, sectionY));
        if (index >= 0) {
            this.lastSectionX = sectionX;
            this.lastSectionY = sectionY;
            this.lastSectionZ = sectionZ;
            this.lastIndex = index;
            return this.descriptors[index];
        }
        return null;
    }

    public SectionDescriptor getOrBuild(ChunkAccess chunk, int sectionY) {
        long key = key(chunk.getPos(), sectionY);
        int index = this.indexByKey.get(key);
        if (index >= 0 && this.chunks[index] == chunk) {
            return this.descriptors[index];
        }
        return this.buildNew(chunk, sectionY, key);
    }

    public void buildChunk(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        for (int i = 0; i < sections.length; i++) {
            this.getOrBuild(chunk, minSection + i);
        }
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

    private static long key(ChunkPos pos, int sectionY) {
        return key(pos.x, pos.z, sectionY);
    }

    private static long key(int sectionX, int sectionZ, int sectionY) {
        return (((long) sectionX) & 0x3FFFFFFL) << 38 | (((long) sectionZ) & 0x3FFFFFFL) << 12 | ((long) sectionY & 0xFFFL);
    }
}
