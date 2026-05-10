package dev.sixik.generator_accelerator.common.utils;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

public final class FastPositionalRandom {
    private static final long LEGACY_MASK = 0xFFFFFFFFFFFFL;
    private static final long LEGACY_MULTIPLIER = 25214903917L;
    private static final long LEGACY_INCREMENT = 11L;
    private static final long XOROSHIRO_GOLDEN_RATIO_64 = -7046029254386353131L;
    private static final long XOROSHIRO_SILVER_RATIO_64 = 7640891576956012809L;
    private static final float FLOAT_UNIT = 5.9604645E-8f;
    private static final double DOUBLE_UNIT = (double) 1.110223E-16f;

    private FastPositionalRandom() {
    }

    public static float nextFloatAt(PositionalRandomFactory factory, int x, int y, int z) {
        long coordinateSeed = Mth.getSeed(x, y, z);
        if (factory instanceof LegacyRandomSource.LegacyPositionalRandomFactory legacy) {
            return legacyNextFloat(coordinateSeed ^ legacy.seed);
        }
        if (factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return xoroshiroNextFloat(coordinateSeed ^ xoroshiro.seedLo, xoroshiro.seedHi);
        }
        return factory.at(x, y, z).nextFloat();
    }

    public static double nextDoubleAt(PositionalRandomFactory factory, int x, int y, int z) {
        long coordinateSeed = Mth.getSeed(x, y, z);
        if (factory instanceof LegacyRandomSource.LegacyPositionalRandomFactory legacy) {
            return legacyNextDouble(coordinateSeed ^ legacy.seed);
        }
        if (factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return xoroshiroNextDouble(coordinateSeed ^ xoroshiro.seedLo, xoroshiro.seedHi);
        }
        return factory.at(x, y, z).nextDouble();
    }

    public static int nextIntAt(PositionalRandomFactory factory, int x, int y, int z, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }

        long coordinateSeed = Mth.getSeed(x, y, z);
        if (factory instanceof LegacyRandomSource.LegacyPositionalRandomFactory legacy) {
            return legacyNextInt(coordinateSeed ^ legacy.seed, bound);
        }
        if (factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return xoroshiroNextInt(coordinateSeed ^ xoroshiro.seedLo, xoroshiro.seedHi, bound);
        }
        return factory.at(x, y, z).nextInt(bound);
    }

    private static float legacyNextFloat(long seed) {
        long state = legacyInitialState(seed);
        state = legacyNextState(state);
        return (float) (state >>> 24) * FLOAT_UNIT;
    }

    private static double legacyNextDouble(long seed) {
        long state = legacyInitialState(seed);
        state = legacyNextState(state);
        int high = (int) (state >>> 22);
        state = legacyNextState(state);
        int low = (int) (state >>> 21);
        return (double) (((long) high << 27) + low) * DOUBLE_UNIT;
    }

    private static int legacyNextInt(long seed, int bound) {
        long state = legacyInitialState(seed);
        int value;
        int result;
        if ((bound & bound - 1) == 0) {
            state = legacyNextState(state);
            value = (int) (state >>> 17);
            return (int) ((long) bound * (long) value >> 31);
        }

        do {
            state = legacyNextState(state);
            value = (int) (state >>> 17);
            result = value % bound;
        } while (value - result + (bound - 1) < 0);
        return result;
    }

    private static long legacyInitialState(long seed) {
        return (seed ^ 0x5DEECE66DL) & LEGACY_MASK;
    }

    private static long legacyNextState(long state) {
        return (state * LEGACY_MULTIPLIER + LEGACY_INCREMENT) & LEGACY_MASK;
    }

    private static float xoroshiroNextFloat(long seedLo, long seedHi) {
        long value = xoroshiroFirstLong(seedLo, seedHi);
        return (float) (value >>> 40) * FLOAT_UNIT;
    }

    private static double xoroshiroNextDouble(long seedLo, long seedHi) {
        long value = xoroshiroFirstLong(seedLo, seedHi);
        return (double) (value >>> 11) * DOUBLE_UNIT;
    }

    private static int xoroshiroNextInt(long seedLo, long seedHi, int bound) {
        long lo = seedLo;
        long hi = seedHi;
        if ((lo | hi) == 0L) {
            lo = XOROSHIRO_GOLDEN_RATIO_64;
            hi = XOROSHIRO_SILVER_RATIO_64;
        }

        while (true) {
            long l = lo;
            long h = hi;
            long next = Long.rotateLeft(l + h, 17) + l;
            h ^= l;
            lo = Long.rotateLeft(l, 49) ^ h ^ h << 21;
            hi = Long.rotateLeft(h, 28);

            long unsigned = Integer.toUnsignedLong((int) next);
            long multiplied = unsigned * (long) bound;
            long low = multiplied & 0xFFFFFFFFL;
            if (low >= (long) bound || low >= (long) Integer.remainderUnsigned(~bound + 1, bound)) {
                return (int) (multiplied >>> 32);
            }
        }
    }

    private static long xoroshiroFirstLong(long seedLo, long seedHi) {
        if ((seedLo | seedHi) == 0L) {
            seedLo = XOROSHIRO_GOLDEN_RATIO_64;
            seedHi = XOROSHIRO_SILVER_RATIO_64;
        }
        return Long.rotateLeft(seedLo + seedHi, 17) + seedLo;
    }
}
