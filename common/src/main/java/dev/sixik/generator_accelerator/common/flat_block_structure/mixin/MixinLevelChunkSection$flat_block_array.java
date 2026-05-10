package dev.sixik.generator_accelerator.common.flat_block_structure.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int bts$RAW_BLOCK_DATA_LENGTH = 4096;
    @Unique
    private static final int bts$RAW_BLOCK_DATA_POOL_MAX = Math.max(32,
            Integer.getInteger("ga.flatBlockArray.rawPoolMax", Runtime.getRuntime().availableProcessors() * 64));
    @Unique
    private static final ConcurrentLinkedQueue<int[]> bts$RAW_BLOCK_DATA_POOL = new ConcurrentLinkedQueue<>();
    @Unique
    private static final AtomicInteger bts$RAW_BLOCK_DATA_POOL_SIZE = new AtomicInteger();

    /**
     * Получить сырые данные блоков в виде плоского одномерного массива.
     * Размер массива всегда равен 4096 (16x16x16).
     * Значения внутри - это глобальные ID BlockState (Global Palette ID).
     * @return массив блоков или null, если данные еще не распакованы.
     */
    @Override
    public int @Nullable [] bts$getRawBlockData() {
        return bts$rawBlockData;
    }

    /**
     * Распаковать данные из {@link net.minecraft.world.level.chunk.PalettedContainer}
     * в плоский массив {@code int[]} для сверхбыстрой генерации.
     * Должен вызываться перед началом тяжелых циклов записи.
     */
    @Override
    public void bts$unpackForGeneration() {
        if (this.bts$rawBlockData != null) return;

        int[] raw = bts$acquireRawBlockData();
        this.bts$rawBlockData = raw;

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
                        raw[index] = GA$BlockStateExtension.get(state).bts$getFastId();
                    }
                }
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
        if (raw == null) return;

        states.acquire();
        try {
            short nonAir = 0;
            short tickBlocks = 0;
            short tickFluids = 0;

            BlockState lastState = Blocks.AIR.defaultBlockState();
            int lastStateIndex = -1;

            for (int i = 0; i < bts$RAW_BLOCK_DATA_LENGTH; i++) {
                int stateId = raw[i];
                if(lastStateIndex != stateId) {
                    lastStateIndex = stateId;
                    lastState = FastBlockStateCache.getBlockState(stateId);
                }

                this.states.set(i, lastState);

                if (!lastState.isAir()) {
                    nonAir++;
                    if (lastState.isRandomlyTicking()) {
                        tickBlocks++;
                    }
                }

                FluidState fluidstate = lastState.getFluidState();
                if (!fluidstate.isEmpty() && fluidstate.isRandomlyTicking()) {
                    tickFluids++;
                }
            }

            this.nonEmptyBlockCount = nonAir;
            this.tickingBlockCount = tickBlocks;
            this.tickingFluidCount = tickFluids;
        } finally {
            this.bts$rawBlockData = null;
            bts$releaseRawBlockData(raw);
            states.release();
        }
    }

    @Unique
    private static int[] bts$acquireRawBlockData() {
        int[] raw = bts$RAW_BLOCK_DATA_POOL.poll();
        if (raw == null) {
            return new int[bts$RAW_BLOCK_DATA_LENGTH];
        }
        bts$RAW_BLOCK_DATA_POOL_SIZE.decrementAndGet();
        Arrays.fill(raw, 0);
        return raw;
    }

    @Unique
    private static void bts$releaseRawBlockData(int[] raw) {
        if (raw.length != bts$RAW_BLOCK_DATA_LENGTH) {
            return;
        }
        int size = bts$RAW_BLOCK_DATA_POOL_SIZE.get();
        while (size < bts$RAW_BLOCK_DATA_POOL_MAX) {
            if (bts$RAW_BLOCK_DATA_POOL_SIZE.compareAndSet(size, size + 1)) {
                bts$RAW_BLOCK_DATA_POOL.offer(raw);
                return;
            }
            size = bts$RAW_BLOCK_DATA_POOL_SIZE.get();
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
            raw[index] = GA$BlockStateExtension.get(pState).bts$getFastId();
            oldState = FastBlockStateCache.getBlockState(oldId);
        } else {
            oldState = pUseLocks
                    ? this.states.getAndSet(pX, pY, pZ, pState)
                    : this.states.getAndSetUnchecked(pX, pY, pZ, pState);
        }

        this.bts$updateCounts(oldState, pState);
        return oldState;
    }

    @Unique
    private void bts$updateCounts(BlockState oldState, BlockState newState) {
        FluidState oldFluid = oldState.getFluidState();
        FluidState newFluid = newState.getFluidState();

        if (!oldState.isAir()) {
            this.nonEmptyBlockCount--;
            if (oldState.isRandomlyTicking()) {
                this.tickingBlockCount--;
            }
        }
        if (!oldFluid.isEmpty()) {
            this.nonEmptyBlockCount--;
            if (oldFluid.isRandomlyTicking()) {
                this.tickingFluidCount--;
            }
        }
        if (!newState.isAir()) {
            this.nonEmptyBlockCount++;
            if (newState.isRandomlyTicking()) {
                this.tickingBlockCount++;
            }
        }
        if (!newFluid.isEmpty()) {
            this.nonEmptyBlockCount++;
            if (newFluid.isRandomlyTicking()) {
                this.tickingFluidCount++;
            }
        }
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
}
