package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.common.carver.CarverChunkWriter;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.BitSet;

public final class DecorationPipelineScratch {

    private static final int DEFAULT_CANDIDATE_CAPACITY = 256;
    private static final int DEFAULT_SECTION_BUCKET_CAPACITY = 16;
    private static final int DEFAULT_MODIFIER_BUFFER_CAPACITY = 32;
    private static final int MAX_REUSED_ORE_BITSET_BITS = 262_144;
    private static final int CANDIDATE_MODE_NONE = 0;
    private static final int CANDIDATE_MODE_SIMPLE_BLOCK = 1;

    private static final ThreadLocal<DecorationPipelineScratch> LOCAL = ThreadLocal.withInitial(DecorationPipelineScratch::new);

    public final SectionDescriptorCache descriptors = new SectionDescriptorCache();
    final CarverChunkWriter chunkWriter = new CarverChunkWriter();
    final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    final BlockPos.MutableBlockPos secondMutablePos = new BlockPos.MutableBlockPos();
    final ReusablePipelineFeaturePlaceContext featurePlaceContext = new ReusablePipelineFeaturePlaceContext();
    final PipelineBiomeFeatureCache biomeFeatureCache = new PipelineBiomeFeatureCache();
    public int[] candidateX = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateY = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateZ = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateKernelId = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateNext = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateSectionIndex = new int[DEFAULT_CANDIDATE_CAPACITY];
    public long[] candidateSeed = new long[DEFAULT_CANDIDATE_CAPACITY];
    public long[] candidateSectionKey = new long[DEFAULT_CANDIDATE_CAPACITY];
    public BlockState[] candidateSimpleBlockState = new BlockState[DEFAULT_CANDIDATE_CAPACITY];
    public int candidateCount;
    public int[] sectionBucketHead = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    public int[] sectionBucketTail = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    public long[] sectionBucketKey = new long[DEFAULT_SECTION_BUCKET_CAPACITY];
    public int[] sectionBucketNextInChunk = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    public int sectionBucketCount;
    public int[] chunkBucketHead = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    public int[] chunkBucketTail = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    public long[] chunkBucketKey = new long[DEFAULT_SECTION_BUCKET_CAPACITY];
    public int chunkBucketCount;
    private BitSet oreBitSet = new BitSet();
    private double[] oreVeinData = new double[64 * 4];
    private LongScratchBuffer[] modifierPositionBuffers = new LongScratchBuffer[4];
    private final Long2IntOpenHashMap sectionBucketIndexByKey = new Long2IntOpenHashMap(DEFAULT_SECTION_BUCKET_CAPACITY);
    private final Long2IntOpenHashMap chunkBucketIndexByKey = new Long2IntOpenHashMap(DEFAULT_SECTION_BUCKET_CAPACITY);
    private ChunkAccess descriptorCenterChunk;
    private PipelinePlacementContext placementContext;
    private long descriptorCenterPos;
    private boolean descriptorsPrepared;
    private int candidateMode;
    private int modifierBufferDepth;

    private DecorationPipelineScratch() {
        this.sectionBucketIndexByKey.defaultReturnValue(-1);
        this.chunkBucketIndexByKey.defaultReturnValue(-1);
        this.modifierPositionBuffers[0] = new LongScratchBuffer(DEFAULT_MODIFIER_BUFFER_CAPACITY);
    }

    public static DecorationPipelineScratch local() {
        return LOCAL.get();
    }

    public PipelinePlacementContext placementContext(WorldGenLevel level, ChunkGenerator generator) {
        if (this.placementContext == null) {
            this.placementContext = new PipelinePlacementContext(level, generator);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_FALLBACK_CONTEXT_OBJECTS);
        }
        return this.placementContext;
    }

    public void clear() {
        this.clearCandidateReferences();
        this.candidateCount = 0;
        this.clearSimpleBlockBatchIndex();
        this.candidateMode = CANDIDATE_MODE_NONE;
        this.descriptors.clear();
        this.biomeFeatureCache.clear();
        this.chunkWriter.end();
        this.descriptorCenterChunk = null;
        this.descriptorCenterPos = 0L;
        this.descriptorsPrepared = false;
        this.modifierBufferDepth = 0;
    }

    boolean descriptorsPreparedFor(ChunkAccess chunk) {
        return this.descriptorsPrepared
                && this.descriptorCenterChunk == chunk
                && this.descriptorCenterPos == chunk.getPos().toLong();
    }

    void markDescriptorsPrepared(ChunkAccess chunk) {
        this.descriptorCenterChunk = chunk;
        this.descriptorCenterPos = chunk.getPos().toLong();
        this.descriptorsPrepared = true;
    }

    BitSet clearOreBitSet() {
        if (this.oreBitSet.size() > MAX_REUSED_ORE_BITSET_BITS) {
            this.oreBitSet = new BitSet();
        } else {
            this.oreBitSet.clear();
        }
        return this.oreBitSet;
    }

    double[] ensureOreVeinDataCapacity(int values) {
        if (this.oreVeinData.length < values) {
            this.oreVeinData = new double[values];
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        return this.oreVeinData;
    }

    LongScratchBuffer acquireModifierPositionBuffer() {
        int index = this.modifierBufferDepth;
        if (index >= this.modifierPositionBuffers.length) {
            LongScratchBuffer[] next = new LongScratchBuffer[this.modifierPositionBuffers.length << 1];
            System.arraycopy(this.modifierPositionBuffers, 0, next, 0, this.modifierPositionBuffers.length);
            this.modifierPositionBuffers = next;
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }

        LongScratchBuffer buffer = this.modifierPositionBuffers[index];
        if (buffer == null) {
            buffer = new LongScratchBuffer(DEFAULT_MODIFIER_BUFFER_CAPACITY);
            this.modifierPositionBuffers[index] = buffer;
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        } else {
            buffer.clear();
        }

        this.modifierBufferDepth = index + 1;
        return buffer;
    }

    void releaseModifierPositionBuffer() {
        if (this.modifierBufferDepth <= 0) {
            return;
        }
        int index = this.modifierBufferDepth - 1;
        LongScratchBuffer buffer = this.modifierPositionBuffers[index];
        if (buffer != null) {
            buffer.clear();
        }
        this.modifierBufferDepth = index;
    }

    public void ensureCandidateCapacity(int wanted) {
        int oldLength = this.candidateX.length;
        if (wanted <= oldLength) {
            return;
        }

        int newLength = oldLength;
        while (newLength < wanted) {
            newLength <<= 1;
        }

        this.candidateX = grow(this.candidateX, newLength);
        this.candidateY = grow(this.candidateY, newLength);
        this.candidateZ = grow(this.candidateZ, newLength);
        this.candidateKernelId = grow(this.candidateKernelId, newLength);
        this.candidateNext = grow(this.candidateNext, newLength);
        this.candidateSectionIndex = grow(this.candidateSectionIndex, newLength);
        this.candidateSeed = grow(this.candidateSeed, newLength);
        this.candidateSectionKey = grow(this.candidateSectionKey, newLength);
        this.candidateSimpleBlockState = grow(this.candidateSimpleBlockState, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    public int addCandidate(int x, int y, int z, int kernelId, int sectionIndex, long seed) {
        int index = this.candidateCount;
        this.ensureCandidateCapacity(index + 1);
        this.candidateX[index] = x;
        this.candidateY[index] = y;
        this.candidateZ[index] = z;
        this.candidateKernelId[index] = kernelId;
        this.candidateNext[index] = -1;
        this.candidateSectionIndex[index] = sectionIndex;
        this.candidateSeed[index] = seed;
        this.candidateSectionKey[index] = 0L;
        this.candidateSimpleBlockState[index] = null;
        this.candidateCount = index + 1;
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED);
        return index;
    }

    void beginSimpleBlockBatch() {
        this.candidateCount = 0;
        this.clearSimpleBlockBatchIndex();
        this.candidateMode = CANDIDATE_MODE_SIMPLE_BLOCK;
    }

    boolean isCollectingSimpleBlockBatch() {
        return this.candidateMode == CANDIDATE_MODE_SIMPLE_BLOCK;
    }

    int addSimpleBlockCandidate(BlockState state, int x, int y, int z) {
        int index = this.candidateCount;
        this.ensureCandidateCapacity(index + 1);
        this.candidateX[index] = x;
        this.candidateY[index] = y;
        this.candidateZ[index] = z;
        long sectionKey = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        this.candidateSectionKey[index] = sectionKey;
        this.candidateNext[index] = -1;
        this.candidateSimpleBlockState[index] = state;
        this.candidateCount = index + 1;
        int bucket = this.findOrCreateSectionBucket(sectionKey, x >> 4, z >> 4);
        int tail = this.sectionBucketTail[bucket];
        if (tail >= 0) {
            this.candidateNext[tail] = index;
        } else {
            this.sectionBucketHead[bucket] = index;
        }
        this.sectionBucketTail[bucket] = index;
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED);
        return index;
    }

    void finishSimpleBlockBatch() {
        this.clearCandidateReferences();
        this.candidateCount = 0;
        this.clearSimpleBlockBatchIndex();
        this.candidateMode = CANDIDATE_MODE_NONE;
    }

    private static int[] grow(int[] old, int newLength) {
        int[] next = new int[newLength];
        System.arraycopy(old, 0, next, 0, old.length);
        return next;
    }

    private static long[] grow(long[] old, int newLength) {
        long[] next = new long[newLength];
        System.arraycopy(old, 0, next, 0, old.length);
        return next;
    }

    private static BlockState[] grow(BlockState[] old, int newLength) {
        BlockState[] next = new BlockState[newLength];
        System.arraycopy(old, 0, next, 0, old.length);
        return next;
    }

    private void clearCandidateReferences() {
        for (int i = 0; i < this.candidateCount; i++) {
            this.candidateSimpleBlockState[i] = null;
        }
    }

    private int findOrCreateSectionBucket(long sectionKey, int chunkX, int chunkZ) {
        int existing = this.sectionBucketIndexByKey.get(sectionKey);
        if (existing >= 0) {
            return existing;
        }
        int index = this.sectionBucketCount;
        this.ensureSectionBucketCapacity(index + 1);
        this.sectionBucketKey[index] = sectionKey;
        this.sectionBucketHead[index] = -1;
        this.sectionBucketTail[index] = -1;
        this.sectionBucketNextInChunk[index] = -1;
        this.sectionBucketCount = index + 1;
        this.sectionBucketIndexByKey.put(sectionKey, index);
        this.linkSectionBucketToChunk(index, chunkX, chunkZ);
        return index;
    }

    private void ensureSectionBucketCapacity(int wanted) {
        int oldLength = this.sectionBucketKey.length;
        if (wanted <= oldLength) {
            return;
        }

        int newLength = oldLength;
        while (newLength < wanted) {
            newLength <<= 1;
        }

        this.sectionBucketHead = grow(this.sectionBucketHead, newLength);
        this.sectionBucketTail = grow(this.sectionBucketTail, newLength);
        this.sectionBucketKey = grow(this.sectionBucketKey, newLength);
        this.sectionBucketNextInChunk = grow(this.sectionBucketNextInChunk, newLength);
        this.chunkBucketHead = grow(this.chunkBucketHead, newLength);
        this.chunkBucketTail = grow(this.chunkBucketTail, newLength);
        this.chunkBucketKey = grow(this.chunkBucketKey, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    private void clearSimpleBlockBatchIndex() {
        this.sectionBucketCount = 0;
        this.chunkBucketCount = 0;
        this.sectionBucketIndexByKey.clear();
        this.chunkBucketIndexByKey.clear();
    }

    private void linkSectionBucketToChunk(int sectionBucket, int chunkX, int chunkZ) {
        long chunkKey = ChunkAccessPos.pack(chunkX, chunkZ);
        int chunkBucket = this.findOrCreateChunkBucket(chunkKey);
        int tail = this.chunkBucketTail[chunkBucket];
        if (tail >= 0) {
            this.sectionBucketNextInChunk[tail] = sectionBucket;
        } else {
            this.chunkBucketHead[chunkBucket] = sectionBucket;
        }
        this.chunkBucketTail[chunkBucket] = sectionBucket;
    }

    private int findOrCreateChunkBucket(long chunkKey) {
        int existing = this.chunkBucketIndexByKey.get(chunkKey);
        if (existing >= 0) {
            return existing;
        }

        int index = this.chunkBucketCount;
        this.ensureSectionBucketCapacity(index + 1);
        this.chunkBucketKey[index] = chunkKey;
        this.chunkBucketHead[index] = -1;
        this.chunkBucketTail[index] = -1;
        this.chunkBucketCount = index + 1;
        this.chunkBucketIndexByKey.put(chunkKey, index);
        return index;
    }

    private static final class ChunkAccessPos {
        private static long pack(int chunkX, int chunkZ) {
            return ((long) chunkX & 4294967295L) | (((long) chunkZ & 4294967295L) << 32);
        }
    }
}
