package dev.sixik.generator_accelerator.common.flat_block_structure;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Интерфейс для работы с плоским массивом блоков в секции мира (DOD оптимизация).
 * Позволяет обойти медленный {@link net.minecraft.world.level.chunk.PalettedContainer}
 * при массовой генерации блоков.
 */
public interface LevelChunkSection$FlatBlockArray {

    static LevelChunkSection$FlatBlockArray get(LevelChunkSection section) {
        return (LevelChunkSection$FlatBlockArray) section;
    }

    static int @Nullable [] rawData(LevelChunkSection section) {
        return section instanceof LevelChunkSection$FlatBlockArray flatBlockArray
                ? flatBlockArray.bts$getRawBlockData()
                : null;
    }

    /**
     * Получить сырые данные блоков в виде плоского одномерного массива.
     * Размер массива всегда равен 4096 (16x16x16).
     * Значения внутри - это глобальные ID BlockState (Global Palette ID).
     * @return массив блоков или null, если данные еще не распакованы.
     */
    int @Nullable [] bts$getRawBlockData();

    /**
     * Fast raw write path for dense generation passes. Returns false when the
     * section is not unpacked and the caller must fall back to setBlockState.
     */
    boolean bts$setRawBlockStateForGeneration(int index, int stateId);

    /**
     * Bulk replace a raw section and update vanilla block/fluid counters.
     * Returns false when the section is not unpacked.
     */
    boolean bts$copyRawBlockDataForGeneration(int[] source);

    /**
     * Bulk replace a raw section from a larger section-aligned source array.
     * Implementations can avoid allocating a temporary 4096-int section copy.
     */
    default boolean bts$copyRawBlockDataForGeneration(int[] source, int sourceOffset) {
        if (sourceOffset == 0) {
            return bts$copyRawBlockDataForGeneration(source);
        }
        if (source == null || sourceOffset < 0 || source.length - sourceOffset < 4096) {
            throw new IllegalArgumentException("source section buffer is too small");
        }
        int[] section = new int[4096];
        System.arraycopy(source, sourceOffset, section, 0, section.length);
        return bts$copyRawBlockDataForGeneration(section);
    }

    /**
     * Bulk replace a raw section when the caller already computed vanilla
     * counters for the 4096 source ids.
     */
    default boolean bts$copyRawBlockDataForGeneration(
            int[] source,
            int sourceOffset,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            int lightEmissionCount
    ) {
        return bts$copyRawBlockDataForGeneration(source, sourceOffset);
    }

    /**
     * Returns true when the raw section contains any block state with vanilla light emission.
     * Falls back to vanilla palette scanning when the section is not unpacked.
     */
    boolean bts$maybeHasLightEmission();

    /**
     * Распаковать данные из {@link net.minecraft.world.level.chunk.PalettedContainer}
     * в плоский массив {@code int[]} для сверхбыстрой генерации.
     * Должен вызываться перед началом тяжелых циклов записи.
     */
    void bts$unpackForGeneration();

    /**
     * Сжать обновленный плоский массив обратно в {@link net.minecraft.world.level.chunk.PalettedContainer}
     * для экономии оперативной памяти и совместимости с ванильным рендером/сохранением.
     * После вызова этого метода сырой массив "замораживается" (обнуляется или возвращается в пул).
     */
    void bts$packAndFreeze();
}
