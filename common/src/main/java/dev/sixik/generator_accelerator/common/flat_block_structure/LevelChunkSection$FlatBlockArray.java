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
