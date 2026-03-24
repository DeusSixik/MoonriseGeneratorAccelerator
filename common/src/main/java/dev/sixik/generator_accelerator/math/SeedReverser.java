package dev.sixik.generator_accelerator.math;

import net.minecraft.world.level.levelgen.RandomSupport;

public class SeedReverser {

    // Магические константы из RandomSupport
    public static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    public static final long SILVER_RATIO_64 = 0x6A09E667F3BCC909L; // 7640891576956012809L

    /**
     * Математически точная обратная функция для RandomSupport.mixStafford13(long).
     */
    public static long unmixStafford13(long l) {
        // 1. Обращаем: l = l ^ l >>> 31
        l ^= l >>> 31;
        l ^= l >>> 62;

        // 2. Обращаем: l *= -7723592293110705685L
        // Умножаем на модулярный мультипликативный инверс
        l *= 3573116690164977347L;

        // 3. Обращаем: l = l ^ l >>> 27
        l ^= l >>> 27;
        l ^= l >>> 54;

        // 4. Обращаем: l *= -4658895280553007687L
        l *= -7575587736534282103L;

        // 5. Обращаем: l = l ^ l >>> 30
        l ^= l >>> 30;
        l ^= l >>> 60;

        return l;
    }

    /**
     * Преобразует сгенерированный Seed128bit обратно в исходный long сид.
     */
    public static long seed128bitToLong(RandomSupport.Seed128bit seed128bit) {
        // Шаг 1: Снимаем миксование (mixStafford13) с младшей половины сида (seedLo).
        long m = unmixStafford13(seed128bit.seedLo());

        // Шаг 2: Обращаем изначальный XOR с константой SILVER_RATIO_64
        // (исходный код: long m = l ^ 0x6A09E667F3BCC909L;)
        long originalSeed = m ^ SILVER_RATIO_64;

        // Этого уже достаточно! Но для параноидальной проверки можно
        // убедиться, что seedHi дает тот же самый результат:
        long n = unmixStafford13(seed128bit.seedHi());
        long mFromHi = n - GOLDEN_RATIO_64;
        long seedFromHi = mFromHi ^ SILVER_RATIO_64;
        if (originalSeed != seedFromHi) {
            throw new IllegalStateException("Seed128bit is corrupted or not created from long!");
        }

        return originalSeed;
    }

    /**
     * Пытается преобразовать сгенерированный Seed128bit обратно в исходный long сид.
     * Возвращает null, если генератор был создан не из одиночного long (например, через MD5 или fork()).
     */
    public static Long tryGetOriginalSeed(RandomSupport.Seed128bit seed128bit) {
        long m = unmixStafford13(seed128bit.seedLo());
        long n = unmixStafford13(seed128bit.seedHi());

        // В оригинальном алгоритме n всегда равно m + GOLDEN_RATIO_64.
        // Если это не так, значит сид сгенерирован алгоритмом fromHashOf или fork()
        if (n - m != GOLDEN_RATIO_64) {
            return null; // Нельзя реверсировать, так как не было исходного long
        }

        return m ^ SILVER_RATIO_64;
    }
}
