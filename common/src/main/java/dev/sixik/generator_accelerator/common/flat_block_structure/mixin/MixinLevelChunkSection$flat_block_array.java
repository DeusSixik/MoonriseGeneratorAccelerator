package dev.sixik.generator_accelerator.common.flat_block_structure.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.FlatBlockArrayMetrics;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.jctools.queues.MpmcArrayQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.function.Predicate;

@Mixin(value = LevelChunkSection.class, priority = 999)
public abstract class MixinLevelChunkSection$flat_block_array implements LevelChunkSection$FlatBlockArray {

    @Shadow
    @Final
    public PalettedContainer<BlockState> states;

    @Shadow
    public short nonEmptyBlockCount;

    @Shadow
    public short tickingFluidCount;
    @Shadow
    public short tickingBlockCount;
    @Unique
    private volatile int @Nullable [] bts$rawBlockData;
    @Unique
    private short @Nullable [] bts$rawDirtyIndices;
    @Unique
    private int bts$rawDirtyIndexCount;
    @Unique
    private int bts$rawLightEmissionCount;
    @Unique
    private boolean bts$rawDirtyOverflow;
    @Unique
    private boolean bts$rawStartedOnlyAir;
    @Unique
    private static final int bts$RAW_BLOCK_DATA_LENGTH = 4096;
    @Unique
    private static final int bts$AIR_STATE_ID = Block.getId(Blocks.AIR.defaultBlockState());
    @Unique
    private static final int bts$RAW_BLOCK_DATA_POOL_DEFAULT_MAX =
            Math.max(8192, Runtime.getRuntime().availableProcessors() * 256);
    @Unique
    private static final int bts$RAW_BLOCK_DATA_POOL_MAX = Math.max(32,
            Integer.getInteger("ga.flatBlockArray.rawPoolMax", bts$RAW_BLOCK_DATA_POOL_DEFAULT_MAX));
    @Unique
    private static final int bts$RAW_DIRTY_INDEX_POOL_MAX = Math.max(32,
            Integer.getInteger("ga.flatBlockArray.rawDirtyPoolMax", Math.min(1024, bts$RAW_BLOCK_DATA_POOL_MAX)));
    @Unique
    private static final MpmcArrayQueue<int[]> bts$RAW_BLOCK_DATA_POOL =
            new MpmcArrayQueue<>(bts$RAW_BLOCK_DATA_POOL_MAX);
    @Unique
    private static final MpmcArrayQueue<short[]> bts$RAW_DIRTY_INDEX_POOL =
            new MpmcArrayQueue<>(bts$RAW_DIRTY_INDEX_POOL_MAX);
    @Unique
    private static final Predicate<BlockState> bts$HAS_LIGHT_EMISSION = state -> state.getLightEmission() != 0;
    @Unique
    private static final int bts$RAW_BLOCK_DATA_PREALLOC = Math.max(0, Math.min(
            bts$RAW_BLOCK_DATA_POOL_MAX,
            Integer.getInteger("ga.flatBlockArray.rawPoolPrealloc", bts$RAW_BLOCK_DATA_POOL_MAX)
    ));
    @Unique
    private static final int bts$PACK_STARTED_ONLY_AIR_BITS = Math.max(4, Math.min(8,
            Integer.getInteger("ga.flatBlockArray.packStartedOnlyAirBits", 6)));
    @Unique
    private static final boolean bts$PACK_STARTED_ONLY_AIR_RECOMPUTE_COUNTERS = Boolean.parseBoolean(System.getProperty(
            "ga.flatBlockArray.packStartedOnlyAirRecomputeCounters",
            "true"
    ));
    @Unique
    private static final boolean bts$RAW_DIRECT_DIRTY_INDEX = Boolean.parseBoolean(System.getProperty(
            "ga.flatBlockArray.rawDirectDirtyIndex.enabled",
            "false"
    ));
    @Unique
    private static final int bts$RAW_DIRTY_INDEX_PREALLOC = Math.max(0, Math.min(
            bts$RAW_DIRTY_INDEX_POOL_MAX,
            Integer.getInteger("ga.flatBlockArray.rawDirtyPoolPrealloc", bts$RAW_DIRTY_INDEX_POOL_MAX)
    ));

    static {
        for (int i = 0; i < bts$RAW_BLOCK_DATA_PREALLOC; i++) {
            bts$RAW_BLOCK_DATA_POOL.offer(new int[bts$RAW_BLOCK_DATA_LENGTH]);
        }
        for (int i = 0; i < bts$RAW_DIRTY_INDEX_PREALLOC; i++) {
            bts$RAW_DIRTY_INDEX_POOL.offer(new short[bts$RAW_BLOCK_DATA_LENGTH]);
        }
    }

    /**
     * Получить сырые данные блоков в виде плоского одномерного массива.
     * Размер массива всегда равен 4096 (16x16x16).
     * Значения внутри - это глобальные ID BlockState (Global Palette ID).
     * @return массив блоков или null, если данные еще не распакованы.
     */
    @Override
    public int @Nullable [] bts$getRawBlockData() {
        int[] raw = this.bts$rawBlockData;
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawData(raw != null);
        }
        return raw;
    }

    @Override
    public boolean bts$setRawBlockStateForGeneration(int index, int stateId) {
        int[] raw = this.bts$rawBlockData;
        if (raw == null) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawWriteMiss();
            }
            return false;
        }

        int oldId = raw[index];
        if (oldId == stateId) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawWriteHit(false);
            }
            return true;
        }

        raw[index] = stateId;
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawWriteHit(true);
        }
        this.bts$updateRawLightEmissionCount(oldId, stateId);
        if (this.bts$rawStartedOnlyAir) {
            if (bts$RAW_DIRECT_DIRTY_INDEX) {
                this.bts$recordRawDirtyIndex(index, oldId, stateId);
            } else if (!this.bts$rawDirtyOverflow) {
                this.bts$rawDirtyOverflow = true;
            }
            if (oldId == bts$AIR_STATE_ID && stateId != bts$AIR_STATE_ID) {
                this.bts$incrementCountsById(stateId);
            } else {
                this.bts$updateCountsById(oldId, stateId);
            }
            return true;
        }
        this.bts$updateCountsById(oldId, stateId);
        return true;
    }

    @Override
    public int bts$setRawBlockStateStartedOnlyAirNoCountersForGeneration(int index, int stateId) {
        int[] raw = this.bts$rawBlockData;
        if (raw == null || !this.bts$rawStartedOnlyAir) {
            if (raw == null && FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawWriteMiss();
            }
            return -1;
        }

        int oldId = raw[index];
        if (oldId == stateId) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawWriteHit(false);
            }
            return 0;
        }
        if (oldId != bts$AIR_STATE_ID || stateId == bts$AIR_STATE_ID) {
            return -1;
        }

        raw[index] = stateId;
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawWriteHit(true);
        }
        if (bts$RAW_DIRECT_DIRTY_INDEX) {
            this.bts$recordRawDirtyIndex(index, oldId, stateId);
        } else if (!this.bts$rawDirtyOverflow) {
            this.bts$rawDirtyOverflow = true;
        }
        return 1;
    }

    @Override
    public boolean bts$isRawStartedOnlyAirForGeneration() {
        return this.bts$rawBlockData != null && this.bts$rawStartedOnlyAir;
    }

    @Override
    public void bts$commitRawStartedOnlyAirGenerationWrites(
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            int lightEmissionCount,
            int writtenBlocks
    ) {
        if (writtenBlocks <= 0 || this.bts$rawBlockData == null || !this.bts$rawStartedOnlyAir) {
            return;
        }
        this.nonEmptyBlockCount = (short) Math.min(bts$RAW_BLOCK_DATA_LENGTH, this.nonEmptyBlockCount + nonEmptyBlockCount);
        this.tickingBlockCount = (short) Math.min(bts$RAW_BLOCK_DATA_LENGTH, this.tickingBlockCount + tickingBlockCount);
        this.tickingFluidCount = (short) Math.min(bts$RAW_BLOCK_DATA_LENGTH, this.tickingFluidCount + tickingFluidCount);
        this.bts$rawLightEmissionCount = Math.min(
                bts$RAW_BLOCK_DATA_LENGTH,
                this.bts$rawLightEmissionCount + lightEmissionCount
        );
        if (!bts$RAW_DIRECT_DIRTY_INDEX && !this.bts$rawDirtyOverflow) {
            this.bts$rawDirtyOverflow = true;
        }
    }

    @Override
    public boolean bts$fillRawBlockStateBoxForGeneration(
            int minLocalX,
            int maxLocalX,
            int minLocalY,
            int maxLocalY,
            int minLocalZ,
            int maxLocalZ,
            int stateId
    ) {
        int[] raw = this.bts$rawBlockData;
        if (raw == null
                || minLocalX < 0 || maxLocalX > 16 || minLocalX >= maxLocalX
                || minLocalY < 0 || maxLocalY > 16 || minLocalY >= maxLocalY
                || minLocalZ < 0 || maxLocalZ > 16 || minLocalZ >= maxLocalZ) {
            return false;
        }

        int touched = 0;
        int removedNonEmpty = 0;
        int removedTickingBlocks = 0;
        int removedTickingFluids = 0;
        int removedLight = 0;
        int changed = 0;
        int previousOldId = Integer.MIN_VALUE;
        boolean oldEmpty = true;
        boolean oldTickingBlock = false;
        boolean oldFluidEmpty = true;
        boolean oldTickingFluid = false;
        boolean oldLight = false;

        for (int y = minLocalY; y < maxLocalY; y++) {
            for (int z = minLocalZ; z < maxLocalZ; z++) {
                int from = (y << 8) | (z << 4) | minLocalX;
                int to = from + (maxLocalX - minLocalX);
                for (int index = from; index < to; index++) {
                    int oldId = raw[index];
                    touched++;
                    if (oldId == stateId) {
                        continue;
                    }
                    if (oldId != previousOldId) {
                        previousOldId = oldId;
                        oldEmpty = FastBlockStateCache.isEmpty(oldId);
                        oldTickingBlock = FastBlockStateCache.isRandomlyTickingBlock(oldId);
                        oldFluidEmpty = FastBlockStateCache.isFluidEmpty(oldId);
                        oldTickingFluid = FastBlockStateCache.isRandomlyTickingFluid(oldId);
                        oldLight = FastBlockStateCache.hasLightEmission(oldId);
                    }
                    if (!oldEmpty) {
                        removedNonEmpty++;
                        if (oldTickingBlock) {
                            removedTickingBlocks++;
                        }
                    }
                    if (!oldFluidEmpty && oldTickingFluid) {
                        removedTickingFluids++;
                    }
                    if (oldLight) {
                        removedLight++;
                    }
                    changed++;
                }
                Arrays.fill(raw, from, to, stateId);
            }
        }

        if (changed == 0) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordFill(touched, changed);
            }
            return true;
        }

        boolean newEmpty = FastBlockStateCache.isEmpty(stateId);
        boolean newTickingBlock = FastBlockStateCache.isRandomlyTickingBlock(stateId);
        boolean newFluidEmpty = FastBlockStateCache.isFluidEmpty(stateId);
        boolean newTickingFluid = FastBlockStateCache.isRandomlyTickingFluid(stateId);
        boolean newLight = FastBlockStateCache.hasLightEmission(stateId);
        this.nonEmptyBlockCount += (short) ((newEmpty ? 0 : changed) - removedNonEmpty);
        this.tickingBlockCount += (short) ((newTickingBlock ? changed : 0) - removedTickingBlocks);
        this.tickingFluidCount += (short) ((!newFluidEmpty && newTickingFluid ? changed : 0) - removedTickingFluids);
        this.bts$rawLightEmissionCount += (newLight ? changed : 0) - removedLight;
        if (this.bts$rawStartedOnlyAir) {
            this.bts$rawDirtyOverflow = true;
        }
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordFill(touched, changed);
        }
        return true;
    }

    @Override
    public boolean bts$copyRawBlockDataForGeneration(int[] source) {
        return this.bts$copyRawBlockDataForGeneration(source, 0);
    }

    @Override
    public boolean bts$copyRawBlockDataForGeneration(int[] source, int sourceOffset) {
        int[] raw = this.bts$rawBlockData;
        if (raw == null) {
            return false;
        }
        if (source == null || sourceOffset < 0 || source.length - sourceOffset < bts$RAW_BLOCK_DATA_LENGTH) {
            throw new IllegalArgumentException("source section buffer is too small");
        }

        System.arraycopy(source, sourceOffset, raw, 0, bts$RAW_BLOCK_DATA_LENGTH);
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordCopy(false);
        }
        this.bts$rawStartedOnlyAir = false;
        this.bts$rawDirtyOverflow = true;
        this.bts$rawDirtyIndexCount = 0;
        this.bts$releaseRawDirtyIndices();
        this.bts$recomputeRawCounters(raw);
        return true;
    }

    @Override
    public boolean bts$copyRawBlockDataForGeneration(
            int[] source,
            int sourceOffset,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            int lightEmissionCount
    ) {
        int[] raw = this.bts$rawBlockData;
        if (raw == null) {
            return false;
        }
        if (source == null || sourceOffset < 0 || source.length - sourceOffset < bts$RAW_BLOCK_DATA_LENGTH) {
            throw new IllegalArgumentException("source section buffer is too small");
        }

        System.arraycopy(source, sourceOffset, raw, 0, bts$RAW_BLOCK_DATA_LENGTH);
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordCopy(true);
        }
        this.bts$rawStartedOnlyAir = false;
        this.bts$rawDirtyOverflow = true;
        this.bts$rawDirtyIndexCount = 0;
        this.bts$releaseRawDirtyIndices();
        this.nonEmptyBlockCount = (short) Math.max(0, Math.min(bts$RAW_BLOCK_DATA_LENGTH, nonEmptyBlockCount));
        this.tickingBlockCount = (short) Math.max(0, Math.min(bts$RAW_BLOCK_DATA_LENGTH, tickingBlockCount));
        this.tickingFluidCount = (short) Math.max(0, Math.min(bts$RAW_BLOCK_DATA_LENGTH, tickingFluidCount));
        this.bts$rawLightEmissionCount = Math.max(0, Math.min(bts$RAW_BLOCK_DATA_LENGTH, lightEmissionCount));
        return true;
    }

    /**
     * Распаковать данные из {@link net.minecraft.world.level.chunk.PalettedContainer}
     * в плоский массив {@code int[]} для сверхбыстрой генерации.
     * Должен вызываться перед началом тяжелых циклов записи.
     */
    @Override
    public void bts$unpackForGeneration() {
        if (this.bts$rawBlockData != null) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordUnpackSkip();
            }
            return;
        }

        long metricsStart = FlatBlockArrayMetrics.ENABLED ? FlatBlockArrayMetrics.startTimer() : 0L;
        try {
        int[] raw = bts$acquireRawBlockData();
        this.bts$rawBlockData = raw;
        this.bts$releaseRawDirtyIndices();
        this.bts$rawDirtyIndexCount = 0;
        this.bts$rawLightEmissionCount = 0;
        this.bts$rawDirtyOverflow = false;

        this.bts$rawStartedOnlyAir = this.nonEmptyBlockCount == 0;
        if (this.nonEmptyBlockCount == 0) {
            return;
        }

        // Copy data from PalettedContainer
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = this.states.get(x, y, z);

                    if (!state.isAir()) {
                        int index = (y << 8) | (z << 4) | x;
                        int stateId = GA$BlockStateExtension.get(state).bts$getFastId();
                        raw[index] = stateId;
                        if (FastBlockStateCache.hasLightEmission(stateId)) {
                            this.bts$rawLightEmissionCount++;
                        }
                    }
                }
            }
        }
        } finally {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordUnpack(metricsStart);
            }
        }
    }

    /**
     * Сжать обновленный плоский массив обратно в {@link net.minecraft.world.level.chunk.PalettedContainer}
     * для экономии оперативной памяти и совместимости с ванильным рендером/сохранением.
     * После вызова этого метода сырой массив "замораживается" (обнуляется или возвращается в пул).
     */
    @Override
    public void bts$packAndFreeze() {
        int[] raw = this.bts$rawBlockData;
        if (raw == null) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordPackSkip();
            }
            return;
        }

        long metricsStart = FlatBlockArrayMetrics.ENABLED ? FlatBlockArrayMetrics.startTimer() : 0L;
        boolean startedOnlyAir = this.bts$rawStartedOnlyAir;
        try {
            if (startedOnlyAir) {
                this.bts$packStartedOnlyAir(raw);
            } else {
                this.bts$packFullSection(raw);
            }
        } finally {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordPack(metricsStart, startedOnlyAir);
            }
            this.bts$rawStartedOnlyAir = false;
            this.bts$rawBlockData = null;
            this.bts$rawDirtyIndexCount = 0;
            this.bts$rawLightEmissionCount = 0;
            this.bts$rawDirtyOverflow = false;
            this.bts$releaseRawDirtyIndices();
            bts$releaseRawBlockData(raw);
        }
    }

    @Unique
    private void bts$packFullSection(int[] raw) {
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordFullSectionScan();
        }
        states.acquire();
        try {
            short nonAir = 0;
            short tickBlocks = 0;
            short tickFluids = 0;

            BlockState lastState = Blocks.AIR.defaultBlockState();
            int lastStateIndex = -1;
            boolean lastEmpty = true;
            boolean lastBlockTicking = false;
            boolean lastFluidEmpty = true;
            boolean lastFluidTicking = false;

            for (int i = 0; i < bts$RAW_BLOCK_DATA_LENGTH; i++) {
                int stateId = raw[i];
                if(lastStateIndex != stateId) {
                    lastStateIndex = stateId;
                    lastState = FastBlockStateCache.getBlockState(stateId);
                    lastEmpty = FastBlockStateCache.isEmpty(stateId);
                    lastBlockTicking = FastBlockStateCache.isRandomlyTickingBlock(stateId);
                    lastFluidEmpty = FastBlockStateCache.isFluidEmpty(stateId);
                    lastFluidTicking = FastBlockStateCache.isRandomlyTickingFluid(stateId);
                }

                this.states.set(i, lastState);

                if (!lastEmpty) {
                    nonAir++;
                    if (lastBlockTicking) {
                        tickBlocks++;
                    }
                }

                if (!lastFluidEmpty) {
                    if (lastFluidTicking) {
                        tickFluids++;
                    }
                }
            }

            this.nonEmptyBlockCount = nonAir;
            this.tickingBlockCount = tickBlocks;
            this.tickingFluidCount = tickFluids;
        } finally {
            states.release();
        }
    }

    @Unique
    private void bts$packStartedOnlyAir(int[] raw) {
        short[] dirtyIndices = this.bts$rawDirtyIndices;
        if (!this.bts$rawDirtyOverflow) {
            if (dirtyIndices == null || this.bts$rawDirtyIndexCount == 0) {
                return;
            }
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordStartedOnlyAirDirtyPack();
            }
            this.bts$packStartedOnlyAirDirty(raw, dirtyIndices, this.bts$rawDirtyIndexCount);
            return;
        }

        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordStartedOnlyAirFullScanPack();
        }
        if (bts$PACK_STARTED_ONLY_AIR_RECOMPUTE_COUNTERS) {
            this.bts$packStartedOnlyAirRecomputeCounters(raw);
            return;
        }

        BlockState lastState = Blocks.AIR.defaultBlockState();
        int lastStateIndex = -1;
        boolean acquired = false;

        try {
            for (int i = 0; i < bts$RAW_BLOCK_DATA_LENGTH; i++) {
                int stateId = raw[i];
                if (stateId == bts$AIR_STATE_ID) {
                    continue;
                }
                if (lastStateIndex != stateId) {
                    lastStateIndex = stateId;
                    lastState = FastBlockStateCache.getBlockState(stateId);
                }

                if (!acquired) {
                    states.acquire();
                    acquired = true;
                    this.states.onResize(bts$PACK_STARTED_ONLY_AIR_BITS, lastState);
                }
                this.states.set(i, lastState);
            }
        } finally {
            if (acquired) {
                states.release();
            }
        }
    }

    @Unique
    private void bts$packStartedOnlyAirRecomputeCounters(int[] raw) {
        short nonAir = 0;
        short tickBlocks = 0;
        short tickFluids = 0;

        BlockState lastState = Blocks.AIR.defaultBlockState();
        int lastStateIndex = -1;
        boolean lastEmpty = true;
        boolean lastBlockTicking = false;
        boolean lastFluidEmpty = true;
        boolean lastFluidTicking = false;
        boolean acquired = false;

        try {
            for (int i = 0; i < bts$RAW_BLOCK_DATA_LENGTH; i++) {
                int stateId = raw[i];
                if (stateId == bts$AIR_STATE_ID) {
                    continue;
                }
                if (lastStateIndex != stateId) {
                    lastStateIndex = stateId;
                    lastState = FastBlockStateCache.getBlockState(stateId);
                    lastEmpty = FastBlockStateCache.isEmpty(stateId);
                    lastBlockTicking = FastBlockStateCache.isRandomlyTickingBlock(stateId);
                    lastFluidEmpty = FastBlockStateCache.isFluidEmpty(stateId);
                    lastFluidTicking = FastBlockStateCache.isRandomlyTickingFluid(stateId);
                }

                if (!acquired) {
                    states.acquire();
                    acquired = true;
                    this.states.onResize(bts$PACK_STARTED_ONLY_AIR_BITS, lastState);
                }
                this.states.set(i, lastState);

                if (!lastEmpty) {
                    nonAir++;
                    if (lastBlockTicking) {
                        tickBlocks++;
                    }
                }

                if (!lastFluidEmpty && lastFluidTicking) {
                    tickFluids++;
                }
            }

            this.nonEmptyBlockCount = nonAir;
            this.tickingBlockCount = tickBlocks;
            this.tickingFluidCount = tickFluids;
        } finally {
            if (acquired) {
                states.release();
            }
        }
    }

    @Unique
    private void bts$packStartedOnlyAirDirty(int[] raw, short[] dirtyIndices, int dirtyCount) {
        BlockState lastState = Blocks.AIR.defaultBlockState();
        int lastStateIndex = -1;
        boolean acquired = false;

        try {
            for (int i = 0; i < dirtyCount; i++) {
                int index = dirtyIndices[i] & 0xFFFF;
                int stateId = raw[index];
                if (stateId == bts$AIR_STATE_ID) {
                    continue;
                }
                if (lastStateIndex != stateId) {
                    lastStateIndex = stateId;
                    lastState = FastBlockStateCache.getBlockState(stateId);
                }

                if (!acquired) {
                    states.acquire();
                    acquired = true;
                }
                this.states.set(index, lastState);
            }
        } finally {
            if (acquired) {
                states.release();
            }
        }
    }

    @Unique
    private static int[] bts$acquireRawBlockData() {
        int[] raw = bts$RAW_BLOCK_DATA_POOL.poll();
        if (raw == null) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawPoolMiss();
            }
            raw = new int[bts$RAW_BLOCK_DATA_LENGTH];
            if (bts$AIR_STATE_ID != 0) {
                Arrays.fill(raw, bts$AIR_STATE_ID);
            }
            return raw;
        }
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawPoolHit();
        }
        Arrays.fill(raw, bts$AIR_STATE_ID);
        return raw;
    }

    @Unique
    private static void bts$releaseRawBlockData(int[] raw) {
        if (raw.length != bts$RAW_BLOCK_DATA_LENGTH) {
            return;
        }
        bts$RAW_BLOCK_DATA_POOL.offer(raw);
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawPoolRelease();
        }
    }

    @Unique
    private static short[] bts$acquireRawDirtyIndices() {
        short[] indices = bts$RAW_DIRTY_INDEX_POOL.poll();
        if (indices == null) {
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawDirtyPoolMiss();
            }
            return new short[bts$RAW_BLOCK_DATA_LENGTH];
        }
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordRawDirtyPoolHit();
        }
        return indices;
    }

    @Unique
    private static void bts$releaseRawDirtyIndices(short[] indices) {
        if (indices.length == bts$RAW_BLOCK_DATA_LENGTH) {
            bts$RAW_DIRTY_INDEX_POOL.offer(indices);
            if (FlatBlockArrayMetrics.ENABLED) {
                FlatBlockArrayMetrics.recordRawDirtyPoolRelease();
            }
        }
    }

    @Unique
    private void bts$releaseRawDirtyIndices() {
        short[] indices = this.bts$rawDirtyIndices;
        if (indices != null) {
            this.bts$rawDirtyIndices = null;
            bts$releaseRawDirtyIndices(indices);
        }
    }

    /**
     * @author Sixik
     * @reason Avoid CallbackInfoReturnable allocation in hot section access.
     */
    @Overwrite
    public PalettedContainer<BlockState> getStates() {
        this.bts$packAndFreeze();
        return this.states;
    }

    /**
     * @author Sixik
     * @reason Avoid CallbackInfoReturnable allocation in hot block reads.
     */
    @Overwrite
    public BlockState getBlockState(int pX, int pY, int pZ) {
        int[] raw = this.bts$rawBlockData;
        if (raw != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            return FastBlockStateCache.getBlockState(raw[index]);
        }
        return this.states.get(pX, pY, pZ);
    }

    /**
     * @author Sixik
     * @reason Avoid CallbackInfoReturnable allocation in hot fluid reads.
     */
    @Overwrite
    public FluidState getFluidState(int pX, int pY, int pZ) {
        int[] raw = this.bts$rawBlockData;
        if (raw != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            return FastBlockStateCache.getBlockState(raw[index]).getFluidState();
        }
        return this.states.get(pX, pY, pZ).getFluidState();
    }

    /**
     * @author Sixik
     * @reason Avoid CallbackInfoReturnable allocation in hot block writes.
     */
    @Overwrite
    public BlockState setBlockState(int pX, int pY, int pZ, BlockState pState, boolean pUseLocks) {
        int[] raw = this.bts$rawBlockData;
        BlockState oldState;
        if (raw != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            int oldId = raw[index];
            int newId = GA$BlockStateExtension.get(pState).bts$getFastId();
            if (oldId != newId) {
                raw[index] = newId;
                this.bts$recordRawDirtyIndex(index, oldId, newId);
                this.bts$updateCountsById(oldId, newId);
                this.bts$updateRawLightEmissionCount(oldId, newId);
            }
            oldState = FastBlockStateCache.getBlockState(oldId);
        } else {
            oldState = pUseLocks
                    ? this.states.getAndSet(pX, pY, pZ, pState)
                    : this.states.getAndSetUnchecked(pX, pY, pZ, pState);
            this.bts$updateCountsById(
                    GA$BlockStateExtension.get(oldState).bts$getFastId(),
                    GA$BlockStateExtension.get(pState).bts$getFastId()
            );
        }
        return oldState;
    }

    @Unique
    private void bts$recordRawDirtyIndex(int index, int oldId, int newId) {
        if (!this.bts$rawStartedOnlyAir || oldId != bts$AIR_STATE_ID || newId == bts$AIR_STATE_ID || this.bts$rawDirtyOverflow) {
            return;
        }
        short[] dirtyIndices = this.bts$rawDirtyIndices;
        if (dirtyIndices == null) {
            dirtyIndices = bts$acquireRawDirtyIndices();
            this.bts$rawDirtyIndices = dirtyIndices;
        }
        int count = this.bts$rawDirtyIndexCount;
        if (count >= dirtyIndices.length) {
            this.bts$rawDirtyOverflow = true;
            return;
        }
        dirtyIndices[count] = (short) index;
        this.bts$rawDirtyIndexCount = count + 1;
    }

    @Unique
    private void bts$updateCountsById(int oldId, int newId) {
        if (oldId == newId) {
            return;
        }
        if (!FastBlockStateCache.isEmpty(oldId)) {
            this.nonEmptyBlockCount--;
            if (FastBlockStateCache.isRandomlyTickingBlock(oldId)) {
                this.tickingBlockCount--;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(oldId)) {
            if (FastBlockStateCache.isRandomlyTickingFluid(oldId)) {
                this.tickingFluidCount--;
            }
        }
        if (!FastBlockStateCache.isEmpty(newId)) {
            this.nonEmptyBlockCount++;
            if (FastBlockStateCache.isRandomlyTickingBlock(newId)) {
                this.tickingBlockCount++;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(newId)) {
            if (FastBlockStateCache.isRandomlyTickingFluid(newId)) {
                this.tickingFluidCount++;
            }
        }
    }

    @Unique
    private void bts$incrementCountsById(int stateId) {
        if (!FastBlockStateCache.isEmpty(stateId)) {
            this.nonEmptyBlockCount++;
            if (FastBlockStateCache.isRandomlyTickingBlock(stateId)) {
                this.tickingBlockCount++;
            }
        }
        if (!FastBlockStateCache.isFluidEmpty(stateId)) {
            if (FastBlockStateCache.isRandomlyTickingFluid(stateId)) {
                this.tickingFluidCount++;
            }
        }
    }

    @Unique
    private void bts$recomputeRawCounters(int[] raw) {
        if (FlatBlockArrayMetrics.ENABLED) {
            FlatBlockArrayMetrics.recordFullSectionScan();
        }
        short nonAir = 0;
        short tickBlocks = 0;
        short tickFluids = 0;
        int lightEmission = 0;
        int lastStateId = -1;
        boolean lastEmpty = true;
        boolean lastBlockTicking = false;
        boolean lastFluidEmpty = true;
        boolean lastFluidTicking = false;
        boolean lastLight = false;

        for (int i = 0; i < bts$RAW_BLOCK_DATA_LENGTH; i++) {
            int stateId = raw[i];
            if (stateId != lastStateId) {
                lastStateId = stateId;
                lastEmpty = FastBlockStateCache.isEmpty(stateId);
                lastBlockTicking = FastBlockStateCache.isRandomlyTickingBlock(stateId);
                lastFluidEmpty = FastBlockStateCache.isFluidEmpty(stateId);
                lastFluidTicking = FastBlockStateCache.isRandomlyTickingFluid(stateId);
                lastLight = FastBlockStateCache.hasLightEmission(stateId);
            }
            if (!lastEmpty) {
                nonAir++;
                if (lastBlockTicking) {
                    tickBlocks++;
                }
            }
            if (!lastFluidEmpty && lastFluidTicking) {
                tickFluids++;
            }
            if (lastLight) {
                lightEmission++;
            }
        }

        this.nonEmptyBlockCount = nonAir;
        this.tickingBlockCount = tickBlocks;
        this.tickingFluidCount = tickFluids;
        this.bts$rawLightEmissionCount = lightEmission;
    }

    @Unique
    private void bts$updateRawLightEmissionCount(int oldId, int newId) {
        boolean oldLight = FastBlockStateCache.hasLightEmission(oldId);
        boolean newLight = FastBlockStateCache.hasLightEmission(newId);
        if (oldLight == newLight) {
            return;
        }
        this.bts$rawLightEmissionCount += newLight ? 1 : -1;
    }

    /**
     * @author Sixik
     * @reason Avoid CallbackInfoReturnable allocation in hot section predicates.
     */
    @Overwrite
    public boolean maybeHas(Predicate<BlockState> predicate) {
        int[] raw = this.bts$rawBlockData;
        if (raw != null) {
            for (int i = 0; i < raw.length; i++) {
                if (predicate.test(FastBlockStateCache.getBlockState(raw[i]))) {
                    return true;
                }
            }
            return false;
        }
        return this.states.maybeHas(predicate);
    }

    @Override
    public boolean bts$maybeHasLightEmission() {
        int[] raw = this.bts$rawBlockData;
        if (raw != null) {
            return this.bts$rawLightEmissionCount > 0;
        }
        return this.states.maybeHas(bts$HAS_LIGHT_EMISSION);
    }
}
