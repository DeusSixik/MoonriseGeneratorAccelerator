package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.carver.CarverChunkWriter;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Array;
import java.util.Arrays;

public final class DecorationPipelineScratch {

    private static final int DEFAULT_CANDIDATE_CAPACITY = 2048;
    private static final int DEFAULT_SECTION_BUCKET_CAPACITY = 128;
    private static final int DEFAULT_MODIFIER_BUFFER_CAPACITY = 32;
    private static final int MAX_RETAINED_CANDIDATE_CAPACITY = 65_536;
    private static final int MAX_EXCESSIVE_CANDIDATE_CAPACITY = 262_144;
    private static final int MAX_RETAINED_SECTION_BUCKET_CAPACITY = 8_192;
    private static final int MAX_EXCESSIVE_SECTION_BUCKET_CAPACITY = 32_768;
    private static final int MAX_RETAINED_TOUCHED_MUTATION_CAPACITY = 8_192;
    private static final int MAX_EXCESSIVE_TOUCHED_MUTATION_CAPACITY = 32_768;
    private static final int MAX_RETAINED_MODIFIER_BUFFER_DEPTH = 16;
    private static final int MAX_REUSED_ORE_BITSET_BITS = 262_144;
    private static final int MAX_REUSED_ORE_VISITED_WORDS = MAX_REUSED_ORE_BITSET_BITS >>> 6;
    private static final int MAX_RETAINED_ORE_VISITED_WORDS = 131_072;
    private static final int MAX_RETAINED_ORE_VEIN_DATA_VALUES = 16_384;
    private static final int OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD = 4;
    private static final int HEIGHTMAP_CACHE_SLOTS = 8;
    private static final int HEIGHTMAP_CACHE_SLOT_MASK = HEIGHTMAP_CACHE_SLOTS - 1;
    private static final int HEIGHTMAP_TYPE_COUNT = Heightmap.Types.values().length;
    private static final int CANDIDATE_MODE_NONE = 0;
    private static final int CANDIDATE_MODE_SIMPLE_BLOCK = 1;
    private static final int CANDIDATE_MODE_WRITE_JOURNAL = 2;
    static final int WRITE_FLAG_SIMPLE_BLOCK_SURVIVAL = 1;
    static final int WRITE_FLAG_MARK_ABOVE_FOR_POSTPROCESSING = 2;

    private static final ThreadLocal<DecorationPipelineScratch> LOCAL = ThreadLocal.withInitial(DecorationPipelineScratch::new);

    public final SectionDescriptorCache descriptors = new SectionDescriptorCache();
    final CarverChunkWriter chunkWriter = new CarverChunkWriter();
    final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    final BlockPos.MutableBlockPos secondMutablePos = new BlockPos.MutableBlockPos();
    final ReusablePipelineFeaturePlaceContext featurePlaceContext = new ReusablePipelineFeaturePlaceContext();
    final PipelineBiomeFeatureCache biomeFeatureCache = new PipelineBiomeFeatureCache();

    public long[] candidates = new long[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateKernelId = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] selectedFeatureBuffer = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateNext = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateSectionIndex = new int[DEFAULT_CANDIDATE_CAPACITY];
    public long[] candidateSeed = new long[DEFAULT_CANDIDATE_CAPACITY];
    public long[] candidateSectionKey = new long[DEFAULT_CANDIDATE_CAPACITY];
    public /* BLOCK_STATE */ int[] candidateSimpleBlockState = new int[DEFAULT_CANDIDATE_CAPACITY];
    public int[] candidateWriteFlags = new int[DEFAULT_CANDIDATE_CAPACITY];
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
    private long[] oreVisitedWords = new long[MAX_REUSED_ORE_VISITED_WORDS];
    private double[] oreVeinData = new double[64 * 4];
    private boolean[] lakeMask = new boolean[16 * 16 * 8];
    private final SculkSpreader worldGenSculkSpreader = SculkSpreader.createWorldGenSpreader();
    private LongScratchBuffer[] modifierPositionBuffers = new LongScratchBuffer[4];
    private BlockPos.MutableBlockPos[] modifierMutablePositions = new BlockPos.MutableBlockPos[4];
    private final Long2IntOpenHashMap sectionBucketIndexByKey = new Long2IntOpenHashMap(DEFAULT_SECTION_BUCKET_CAPACITY);
    private final Long2IntOpenHashMap chunkBucketIndexByKey = new Long2IntOpenHashMap(DEFAULT_SECTION_BUCKET_CAPACITY);
    private final Long2IntOpenHashMap writeIndexByPos = new Long2IntOpenHashMap(DEFAULT_CANDIDATE_CAPACITY);
    private final Long2IntOpenHashMap touchedMutationIndexByKey = new Long2IntOpenHashMap(DEFAULT_SECTION_BUCKET_CAPACITY);
    private final ChunkAccess[] heightmapCacheChunks = new ChunkAccess[HEIGHTMAP_CACHE_SLOTS];
    private final long[] heightmapCacheChunkPos = new long[HEIGHTMAP_CACHE_SLOTS];
    private final Heightmap[] heightmapCache = new Heightmap[HEIGHTMAP_CACHE_SLOTS * HEIGHTMAP_TYPE_COUNT];
    private ChunkAccess[] touchedMutationChunk = new ChunkAccess[DEFAULT_SECTION_BUCKET_CAPACITY];
    private int[] touchedMutationX = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    private int[] touchedMutationY = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    private int[] touchedMutationZ = new int[DEFAULT_SECTION_BUCKET_CAPACITY];

    private /* BLOCK_STATE */ int[] touchedMutationState = new int[DEFAULT_SECTION_BUCKET_CAPACITY];
    private int touchedMutationCount;
    private ChunkAccess descriptorCenterChunk;
    private PipelinePlacementContext placementContext;
    private long descriptorCenterPos;
    private boolean descriptorsPrepared;
    private boolean heightmapCacheUsed;
    private int candidateMode;
    private int modifierBufferDepth;
    private int oversizedCandidateClearCount;
    private int oversizedSectionBucketClearCount;
    private int oversizedTouchedMutationClearCount;
    private int oversizedModifierBufferClearCount;
    private int oversizedOreVisitedClearCount;
    private int oversizedOreVeinClearCount;

    private DecorationPipelineScratch() {
        this.sectionBucketIndexByKey.defaultReturnValue(-1);
        this.chunkBucketIndexByKey.defaultReturnValue(-1);
        this.writeIndexByPos.defaultReturnValue(-1);
        this.touchedMutationIndexByKey.defaultReturnValue(-1);
        this.modifierPositionBuffers[0] = new LongScratchBuffer(DEFAULT_MODIFIER_BUFFER_CAPACITY);

        Arrays.fill(this.touchedMutationState, -1);
    }

    public static DecorationPipelineScratch local() {
        return LOCAL.get();
    }

    public PipelinePlacementContext placementContext(WorldGenLevel level, ChunkGenerator generator) {
        if (this.placementContext == null) {
            this.placementContext = new PipelinePlacementContext(level, generator);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_FALLBACK_CONTEXT_OBJECTS);
        }
        this.placementContext.set(level, generator, java.util.Optional.empty(), null);
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
        this.featurePlaceContext.clear();
        if (this.placementContext != null) {
            this.placementContext.clear();
        }
        this.descriptorCenterChunk = null;
        this.descriptorCenterPos = 0L;
        this.descriptorsPrepared = false;
        if (this.heightmapCacheUsed) {
            Arrays.fill(this.heightmapCacheChunks, null);
            Arrays.fill(this.heightmapCache, null);
            this.heightmapCacheUsed = false;
        }
        this.modifierBufferDepth = 0;
        this.shrinkOversizedBuffers();
    }

    boolean descriptorsPreparedFor(ChunkAccess chunk) {
        return this.descriptorsPrepared
                && this.descriptorCenterChunk == chunk
                && this.descriptorCenterPos == chunk.getPos().toLong();
    }

    boolean hasPreparedDescriptors() {
        return this.descriptorsPrepared;
    }

    void markDescriptorsPrepared(ChunkAccess chunk) {
        this.descriptorCenterChunk = chunk;
        this.descriptorCenterPos = chunk.getPos().toLong();
        this.descriptorsPrepared = true;
    }

    long[] clearOreVisitedWords(int bitCount) {
        int wordCount = (bitCount + Long.SIZE - 1) >>> 6;
        if (this.oreVisitedWords.length < wordCount) {
            this.oreVisitedWords = new long[Math.max(wordCount, MAX_REUSED_ORE_VISITED_WORDS)];
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        Arrays.fill(this.oreVisitedWords, 0, wordCount, 0L);
        return this.oreVisitedWords;
    }

    double[] ensureOreVeinDataCapacity(int values) {
        if (this.oreVeinData.length < values) {
            this.oreVeinData = new double[values];
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        return this.oreVeinData;
    }

    boolean[] clearLakeMask() {
        Arrays.fill(this.lakeMask, false);
        return this.lakeMask;
    }

    SculkSpreader worldGenSculkSpreader() {
        this.worldGenSculkSpreader.clear();
        return this.worldGenSculkSpreader;
    }

    Heightmap cachedHeightmap(ChunkAccess chunk, Heightmap.Types type) {
        this.heightmapCacheUsed = true;
        long chunkPos = chunk.getPos().toLong();
        int slot = ((int) (chunkPos ^ (chunkPos >>> 32))) & HEIGHTMAP_CACHE_SLOT_MASK;
        int baseIndex = slot * HEIGHTMAP_TYPE_COUNT;
        if (this.heightmapCacheChunks[slot] != chunk || this.heightmapCacheChunkPos[slot] != chunkPos) {
            Arrays.fill(this.heightmapCache, baseIndex, baseIndex + HEIGHTMAP_TYPE_COUNT, null);
            this.heightmapCacheChunks[slot] = chunk;
            this.heightmapCacheChunkPos[slot] = chunkPos;
        }

        int index = baseIndex + type.ordinal();
        Heightmap heightmap = this.heightmapCache[index];
        if (heightmap == null) {
            heightmap = ((ChunkAccess$getOrCreateHeightmapUnsynchronized) chunk).bts$getOrCreateHeightmapUnsynchronized(type);
            this.heightmapCache[index] = heightmap;
        }
        return heightmap;
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

    BlockPos.MutableBlockPos modifierMutablePos(int depth) {
        if (depth >= this.modifierMutablePositions.length) {
            int newLength = this.modifierMutablePositions.length;
            while (depth >= newLength) {
                newLength <<= 1;
            }
            BlockPos.MutableBlockPos[] next = new BlockPos.MutableBlockPos[newLength];
            System.arraycopy(this.modifierMutablePositions, 0, next, 0, this.modifierMutablePositions.length);
            this.modifierMutablePositions = next;
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        BlockPos.MutableBlockPos pos = this.modifierMutablePositions[depth];
        if (pos == null) {
            pos = new BlockPos.MutableBlockPos();
            this.modifierMutablePositions[depth] = pos;
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
        }
        return pos;
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
        int oldLength = this.candidates.length;
        if (wanted <= oldLength) {
            return;
        }

        int newLength = oldLength;
        while (newLength < wanted) {
            newLength <<= 1;
        }

        this.candidates = grow(this.candidates, newLength);
        this.candidateKernelId = grow(this.candidateKernelId, newLength);
        this.candidateNext = grow(this.candidateNext, newLength);
        this.candidateSectionIndex = grow(this.candidateSectionIndex, newLength);
        this.candidateSeed = grow(this.candidateSeed, newLength);
        this.candidateSectionKey = grow(this.candidateSectionKey, newLength);
        this.candidateSimpleBlockState = grow(this.candidateSimpleBlockState, newLength);
        this.candidateWriteFlags = grow(this.candidateWriteFlags, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    public void ensureSelectedFeatureCapacity(int wanted) {
        int oldLength = this.selectedFeatureBuffer.length;
        if (wanted <= oldLength) {
            return;
        }

        int newLength = oldLength;
        while (newLength < wanted) {
            newLength <<= 1;
        }

        this.selectedFeatureBuffer = grow(this.selectedFeatureBuffer, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    public int addCandidate(int x, int y, int z, int kernelId, int sectionIndex, long seed) {
        int index = this.candidateCount;
        this.ensureCandidateCapacity(index + 1);

        this.candidates[index] = BlockPos.asLong(x, y, z);
        this.candidateKernelId[index] = kernelId;
        this.candidateNext[index] = -1;
        this.candidateSectionIndex[index] = sectionIndex;
        this.candidateSeed[index] = seed;
        this.candidateSectionKey[index] = 0L;
        this.candidateSimpleBlockState[index] = -1;
        this.candidateWriteFlags[index] = 0;
        this.candidateCount = index + 1;
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_CANDIDATES_GENERATED);
        return index;
    }

    void beginSimpleBlockBatch() {
        this.beginWriteJournal(CANDIDATE_MODE_SIMPLE_BLOCK);
    }

    boolean isCollectingSimpleBlockBatch() {
        return this.candidateMode == CANDIDATE_MODE_SIMPLE_BLOCK;
    }

    int addSimpleBlockCandidate(BlockState state, int x, int y, int z) {
        return this.addJournalWrite(state, x, y, z, WRITE_FLAG_SIMPLE_BLOCK_SURVIVAL, false);
    }

    void finishSimpleBlockBatch() {
        this.finishWriteJournal();
    }

    void beginWriteJournal() {
        this.beginWriteJournal(CANDIDATE_MODE_WRITE_JOURNAL);
    }

    boolean isCollectingWriteJournal() {
        return this.candidateMode == CANDIDATE_MODE_WRITE_JOURNAL || this.candidateMode == CANDIDATE_MODE_SIMPLE_BLOCK;
    }

    int addDirectWrite(BlockState state, int x, int y, int z) {
        return this.addJournalWrite(state, x, y, z, 0, true);
    }

    int addDirectWrite(BlockState state, int x, int y, int z, int flags) {
        return this.addJournalWrite(state, x, y, z, flags, true);
    }

    private void beginWriteJournal(int mode) {
        this.candidateCount = 0;
        this.clearWriteJournalIndex();
        this.candidateMode = mode;
    }

    private int addJournalWrite(BlockState state, int x, int y, int z, int flags, boolean dedupe) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_WRITE_CANDIDATES);
        long posKey = 0L;
        if (dedupe) {
            posKey = BlockPos.asLong(x, y, z);
            int existing = this.writeIndexByPos.get(posKey);
            if (existing >= 0) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_COLLISIONS);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.JOURNAL_DEDUPED_WRITES);
                return existing;
            }
        }

        int index = this.candidateCount;
        this.ensureCandidateCapacity(index + 1);
        this.candidates[index] = BlockPos.asLong(x, y, z);
        long sectionKey = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        this.candidateSectionKey[index] = sectionKey;
        this.candidateNext[index] = -1;
        this.candidateSimpleBlockState[index] = GA$BlockStateExtension.get(state).bts$getFastId();
        this.candidateWriteFlags[index] = flags;
        this.candidateCount = index + 1;
        if (dedupe) {
            this.writeIndexByPos.put(posKey, index);
        }
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

    void finishWriteJournal() {
        this.clearCandidateReferences();
        this.candidateCount = 0;
        this.clearWriteJournalIndex();
        this.candidateMode = CANDIDATE_MODE_NONE;
    }

    void noteJournalMutation(ChunkAccess chunk, int blockX, int blockY, int blockZ) {
        long key = BlockPos.asLong(blockX, blockY >> 4, blockZ);
        this.noteJournalMutation(chunk, blockX, blockY, blockZ, -1, key);
    }

    void noteJournalMutation(ChunkAccess chunk, int blockX, int blockY, int blockZ, int state) {
        long key = BlockPos.asLong(blockX, blockY, blockZ);
        this.noteJournalMutation(chunk, blockX, blockY, blockZ, state, key);
    }

    private void noteJournalMutation(ChunkAccess chunk, int blockX, int blockY, int blockZ, int state, long key) {
        int existingIndex = this.touchedMutationIndexByKey.get(key);
        if (existingIndex >= 0) {
            if (state != -1) {
                this.touchedMutationState[existingIndex] = state;
                this.touchedMutationY[existingIndex] = blockY;
            }
            return;
        }
        int index = this.touchedMutationCount;
        this.ensureTouchedMutationCapacity(index + 1);
        this.touchedMutationChunk[index] = chunk;
        this.touchedMutationX[index] = blockX;
        this.touchedMutationY[index] = blockY;
        this.touchedMutationZ[index] = blockZ;
        this.touchedMutationState[index] = state;
        this.touchedMutationCount = index + 1;
        this.touchedMutationIndexByKey.put(key, index);
    }

    void flushJournalDescriptorMutations() {
        for (int i = 0; i < this.touchedMutationCount; i++) {
            this.descriptors.noteBlockMutation(
                    this.touchedMutationChunk[i],
                    this.touchedMutationX[i],
                    this.touchedMutationY[i],
                    this.touchedMutationZ[i],
                    this.touchedMutationState[i]
            );
        }
        DecorationPipelineMetrics.add(DecorationPipelineMetrics.JOURNAL_TOUCHED_SECTION_COLUMNS, this.touchedMutationCount);
        this.clearJournalDescriptorMutations();
    }

    int touchedMutationCount() {
        return this.touchedMutationCount;
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
            this.candidateSimpleBlockState[i] = -1;
            this.candidateWriteFlags[i] = 0;
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

    private void clearWriteJournalIndex() {
        if (this.sectionBucketCount == 0
                && this.chunkBucketCount == 0
                && this.writeIndexByPos.isEmpty()
                && this.touchedMutationCount == 0
                && this.touchedMutationIndexByKey.isEmpty()) {
            return;
        }
        this.sectionBucketCount = 0;
        this.chunkBucketCount = 0;
        this.sectionBucketIndexByKey.clear();
        this.chunkBucketIndexByKey.clear();
        this.writeIndexByPos.clear();
        this.clearJournalDescriptorMutations();
    }

    private void clearSimpleBlockBatchIndex() {
        this.clearWriteJournalIndex();
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

    private void ensureTouchedMutationCapacity(int wanted) {
        int oldLength = this.touchedMutationX.length;
        if (wanted <= oldLength) {
            return;
        }

        int newLength = oldLength;
        while (newLength < wanted) {
            newLength <<= 1;
        }

        ChunkAccess[] chunks = new ChunkAccess[newLength];
        System.arraycopy(this.touchedMutationChunk, 0, chunks, 0, this.touchedMutationChunk.length);
        this.touchedMutationChunk = chunks;
        this.touchedMutationX = grow(this.touchedMutationX, newLength);
        this.touchedMutationY = grow(this.touchedMutationY, newLength);
        this.touchedMutationZ = grow(this.touchedMutationZ, newLength);
        this.touchedMutationState = Arrays.copyOf(this.touchedMutationState, newLength);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.ALLOC_BUFFER_GROWTHS);
    }

    private void clearJournalDescriptorMutations() {
        Arrays.fill(this.touchedMutationChunk, 0, this.touchedMutationCount, null);
        Arrays.fill(this.touchedMutationState, 0, this.touchedMutationCount, -1);
        this.touchedMutationCount = 0;
        this.touchedMutationIndexByKey.clear();
    }

    private void shrinkOversizedBuffers() {
        if (this.candidates.length > MAX_EXCESSIVE_CANDIDATE_CAPACITY) {
            if (++this.oversizedCandidateClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.candidates = new long[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateKernelId = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.selectedFeatureBuffer = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateNext = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateSectionIndex = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateSeed = new long[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateSectionKey = new long[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateSimpleBlockState = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.candidateWriteFlags = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.oversizedCandidateClearCount = 0;
            }
        } else if (this.selectedFeatureBuffer.length > MAX_EXCESSIVE_CANDIDATE_CAPACITY) {
            if (++this.oversizedCandidateClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.selectedFeatureBuffer = new int[MAX_RETAINED_CANDIDATE_CAPACITY];
                this.oversizedCandidateClearCount = 0;
            }
        } else {
            this.oversizedCandidateClearCount = 0;
        }

        if (this.sectionBucketKey.length > MAX_EXCESSIVE_SECTION_BUCKET_CAPACITY) {
            if (++this.oversizedSectionBucketClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.sectionBucketHead = new int[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.sectionBucketTail = new int[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.sectionBucketKey = new long[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.sectionBucketNextInChunk = new int[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.chunkBucketHead = new int[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.chunkBucketTail = new int[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.chunkBucketKey = new long[MAX_RETAINED_SECTION_BUCKET_CAPACITY];
                this.oversizedSectionBucketClearCount = 0;
            }
        } else {
            this.oversizedSectionBucketClearCount = 0;
        }

        if (this.touchedMutationX.length > MAX_EXCESSIVE_TOUCHED_MUTATION_CAPACITY) {
            if (++this.oversizedTouchedMutationClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.touchedMutationChunk = new ChunkAccess[MAX_RETAINED_TOUCHED_MUTATION_CAPACITY];
                this.touchedMutationX = new int[MAX_RETAINED_TOUCHED_MUTATION_CAPACITY];
                this.touchedMutationY = new int[MAX_RETAINED_TOUCHED_MUTATION_CAPACITY];
                this.touchedMutationZ = new int[MAX_RETAINED_TOUCHED_MUTATION_CAPACITY];
                this.oversizedTouchedMutationClearCount = 0;
            }
        } else {
            this.oversizedTouchedMutationClearCount = 0;
        }

        if (this.modifierPositionBuffers.length > MAX_RETAINED_MODIFIER_BUFFER_DEPTH) {
            if (++this.oversizedModifierBufferClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.modifierPositionBuffers = new LongScratchBuffer[4];
                this.modifierPositionBuffers[0] = new LongScratchBuffer(DEFAULT_MODIFIER_BUFFER_CAPACITY);
                this.modifierMutablePositions = new BlockPos.MutableBlockPos[4];
                this.oversizedModifierBufferClearCount = 0;
            }
        } else {
            this.oversizedModifierBufferClearCount = 0;
            for (LongScratchBuffer buffer : this.modifierPositionBuffers) {
                if (buffer != null) {
                    buffer.trimIfExcessivelyOversized();
                }
            }
        }

        if (this.oreVisitedWords.length > MAX_RETAINED_ORE_VISITED_WORDS) {
            if (++this.oversizedOreVisitedClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.oreVisitedWords = new long[MAX_REUSED_ORE_VISITED_WORDS];
                this.oversizedOreVisitedClearCount = 0;
            }
        } else {
            this.oversizedOreVisitedClearCount = 0;
        }

        if (this.oreVeinData.length > MAX_RETAINED_ORE_VEIN_DATA_VALUES) {
            if (++this.oversizedOreVeinClearCount >= OVERSIZED_BUFFER_TRIM_CLEAR_THRESHOLD) {
                this.oreVeinData = new double[64 * 4];
                this.oversizedOreVeinClearCount = 0;
            }
        } else {
            this.oversizedOreVeinClearCount = 0;
        }
    }

    public String debugSummary() {
        return "candidates=" + this.candidateCount
                + '/' + this.candidates.length
                + ",selectedCap=" + this.selectedFeatureBuffer.length
                + ",sectionBuckets=" + this.sectionBucketCount
                + '/' + this.sectionBucketKey.length
                + ",chunkBuckets=" + this.chunkBucketCount
                + '/' + this.chunkBucketKey.length
                + ",touchedMutations=" + this.touchedMutationCount
                + '/' + this.touchedMutationX.length
                + ",oreVisitedWords=" + this.oreVisitedWords.length
                + ",oreVeinData=" + this.oreVeinData.length
                + ",modifierDepth=" + this.modifierBufferDepth
                + '/' + this.modifierPositionBuffers.length
                + ",descriptors={" + this.descriptors.debugSummary() + '}';
    }

    private static final class ChunkAccessPos {
        private static long pack(int chunkX, int chunkZ) {
            return ((long) chunkX & 4294967295L) | (((long) chunkZ & 4294967295L) << 32);
        }
    }
}
