package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;
import java.util.function.Predicate;

@Mixin(Heightmap.class)
public abstract class MixinHeightmap$optimize_logic {

    @Shadow
    @Final private ChunkAccess chunk;
    @Shadow @Final
    private Predicate<BlockState> isOpaque;
    @Shadow @Final private BitStorage data;

    @Shadow protected abstract void setHeight(int x, int z, int y);
    @Shadow protected abstract int getFirstAvailable(int index);
    @Shadow private static int getIndex(int x, int z) { return 0; } // Shadow dummy

    /**
     * @author Sixik
     * @reason Optimize "Hole Punching" by skipping empty sections during downward scan.
     */
    @Overwrite
    public boolean update(int x, int y, int z, BlockState state) {
        int index = getIndex(x, z);
        int currentTop = this.getFirstAvailable(index); // Текущая известная высота + 1

        // 1. Если новый блок ниже текущего верха - ничего не меняем (быстрый выход)
        // (кроме случая, когда мы ломаем блок прямо ПОД текущим верхом? Нет, heightmap хранит Y+1)
        if (y <= currentTop - 2) {
            return false;
        }

        // 2. Если мы ставим блок (state opaque):
        // Если он выше или равен текущему - обновляем верх.
        if (this.isOpaque.test(state)) {
            if (y >= currentTop) {
                this.setHeight(x, z, y + 1);
                return true;
            }
        }
        // 3. Если мы ломаем блок (state transparent), и это был САМЫЙ ВЕРХНИЙ блок:
        // Нам нужно найти новый верх, сканируя вниз.
        else if (currentTop - 1 == y) {
            int minBuildHeight = this.chunk.getMinBuildHeight();
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

            // --- OPTIMIZATION START ---
            // Сканируем вниз от (y - 1)
            int searchY = y - 1;

            while (searchY >= minBuildHeight) {
                // Получаем секцию для текущего Y
                int sectionIndex = this.chunk.getSectionIndex(searchY);
                LevelChunkSection section = this.chunk.getSection(sectionIndex);

                // Если секция пустая (только воздух) и наш предикат игнорирует воздух (почти всегда так)
                // Мы можем пропустить всю секцию или остаток секции.
                if (section.hasOnlyAir()) {
                    // Вычисляем Y начала этой секции
                    int sectionBottomY = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;

                    // Прыгаем сразу на дно секции минус 1 (переход в следующую)
                    searchY = sectionBottomY - 1;
                    continue;
                }

                // Если секция не пустая, проверяем блоки внутри неё
                // Но не всю секцию, а только до дна секции
                int sectionBottomY = this.chunk.getSectionYFromSectionIndex(sectionIndex) << 4;

                // Итерируемся внутри секции
                for (; searchY >= sectionBottomY; searchY--) {
                    mutablePos.set(x, searchY, z);

                    // Используем getBlockState(x, y, z) секции было бы быстрее,
                    // но нам нужен chunk context для корректности (редко).
                    // Для безопасности берем state через чанк, но так как мы уже проверили hasOnlyAir,
                    // мы экономим кучу вызовов на пустых секциях.
                    BlockState checkState = this.chunk.getBlockState(mutablePos);

                    if (this.isOpaque.test(checkState)) {
                        this.setHeight(x, z, searchY + 1);
                        return true;
                    }
                }
                // Цикл for закончился (прошли секцию), while продолжит со следующей
            }
            // --- OPTIMIZATION END ---

            // Если дошли до дна и ничего не нашли
            this.setHeight(x, z, minBuildHeight);
            return true;
        }

        return false;
    }

    /**
     * @author Sixik
     * @reason Optimized priming: Uses bitmasks instead of Lists, skips empty sections, zero allocation in loop.
     */
    @Overwrite
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        int typeCount = types.size();
        if (typeCount == 0) return;

        // 1. Подготовка (Pre-calculation)
        // Превращаем Set в массивы, чтобы итерироваться быстрее и без итераторов.
        // Это единственная аллокация (небольшие массивы), что ничтожно мало.
        Heightmap[] maps = new Heightmap[typeCount];
        Predicate<BlockState>[] predicates = new Predicate[typeCount];

        int idx = 0;
        for (Heightmap.Types type : types) {
            maps[idx] = chunk.getOrCreateHeightmapUnprimed(type);
            predicates[idx] = type.isOpaque();
            idx++;
        }

        int highestY = chunk.getHighestSectionPosition() + 16;
        int minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        // 2. Проход по колонкам (X, Z)
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {

                // Битовая маска: если i-й бит равен 1, значит для i-й карты мы еще НЕ нашли высоту.
                // (1 << typeCount) - 1  -> создает маску из единиц (например 0b1111 для 4 типов)
                int remainingBits = (1 << typeCount) - 1;

                // 3. Сканирование сверху вниз (Y)
                // Оптимизация: мы управляем циклом вручную, чтобы прыгать через секции
                for (int y = highestY - 1; y >= minY; y--) {

                    // --- SECTION SKIPPING ---
                    // Проверяем, находимся ли мы на границе секции или внутри пустой секции
                    int sectionIndex = chunk.getSectionIndex(y);
                    LevelChunkSection section = chunk.getSection(sectionIndex);

                    // Если секция пустая (только воздух), мы можем её пропустить.
                    // (Предполагаем, что Heightmap не ищет воздух, что верно для ваниллы)
                    if (section.hasOnlyAir()) {
                        // Вычисляем Y низа секции
                        int sectionBottomY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                        // Прыгаем сразу на дно (цикл сделает y--, поэтому ставим bottomY)
                        y = sectionBottomY;
                        continue;
                    }

                    // --- BLOCK CHECK ---
                    mutablePos.set(x, y, z);
                    BlockState state = chunk.getBlockState(mutablePos);

                    if (!state.isAir()) {
                        // Проверяем все карты, которые еще не заполнены
                        for (int i = 0; i < typeCount; i++) {
                            // Проверяем i-й бит
                            if ((remainingBits & (1 << i)) != 0) {
                                if (predicates[i].test(state)) {
                                    // Нашли! Ставим высоту и убираем бит из маски
                                    maps[i].setHeight(x, z, y + 1);
                                    remainingBits &= ~(1 << i);
                                }
                            }
                        }

                        // Если все карты заполнены (маска 0), прерываем скан этой колонки
                        if (remainingBits == 0) {
                            break;
                        }
                    }
                }

                // 4. Fallback (если дошли до дна и не нашли блоков)
                if (remainingBits != 0) {
                    for (int i = 0; i < typeCount; i++) {
                        if ((remainingBits & (1 << i)) != 0) {
                            maps[i].setHeight(x, z, minY);
                        }
                    }
                }
            }
        }
    }
}
