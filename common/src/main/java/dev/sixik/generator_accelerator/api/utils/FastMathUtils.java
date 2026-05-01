package dev.sixik.generator_accelerator.api.utils;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;

public class FastMathUtils {

    /**
     * Сверхбыстрый shuffle для {@link ObjectArrayList}. <br>
     * Работает напрямую с массивом {@code Object[]} без проверок границ (bounds checking).
     */
    public static void shuffle(ObjectArrayList<?> list, RandomSource random) {
        Object[] array = list.elements();
        int size = list.size();

        for (int i = size; i > 1; i--) {
            int j = random.nextInt(i);

            Object temp = array[i - 1];
            array[i - 1] = array[j];
            array[j] = temp;
        }
    }

    /**
     * Сверхбыстрый shuffle для массивов примитивов ({@link LongArrayList}). <br>
     * НОЛЬ аллокаций, НОЛЬ автоупаковки (boxing), прямое чтение памяти.
     */
    public static void shuffle(LongArrayList list, RandomSource random) {
        long[] array = list.elements();
        int size = list.size();

        for (int i = size; i > 1; i--) {
            int j = random.nextInt(i);
            long temp = array[i - 1];
            array[i - 1] = array[j];
            array[j] = temp;
        }
    }

    /**
     * Для полноты картины: версия для {@link IntArrayList}
     */
    public static void shuffle(IntArrayList list, RandomSource random) {
        int[] array = list.elements();
        int size = list.size();
        for (int i = size; i > 1; i--) {
            int j = random.nextInt(i);
            int temp = array[i - 1];
            array[i - 1] = array[j];
            array[j] = temp;
        }
    }
}
