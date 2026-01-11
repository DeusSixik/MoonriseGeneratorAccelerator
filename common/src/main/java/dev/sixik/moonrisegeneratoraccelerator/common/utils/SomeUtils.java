package dev.sixik.moonrisegeneratoraccelerator.common.utils;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.*;
import org.jetbrains.annotations.NotNull;

import static net.minecraft.world.level.levelgen.XoroshiroRandomSource.*;

public class SomeUtils {

    private static final ThreadLocal<XoroshiroRandomSource> xoroshiro = ThreadLocal.withInitial(() -> new XoroshiroRandomSource(0L, 0L));
    private static final ThreadLocal<SingleThreadedRandomSource> simple = ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(0L));

    public static @NotNull RandomSource getRandom(PositionalRandomFactory deriver) {
        if (deriver instanceof XoroshiroPositionalRandomFactory) {
            return new XoroshiroRandomSource(0L, 0L);
        }
        if (deriver instanceof LegacyRandomSource.LegacyPositionalRandomFactory) {
            return new SingleThreadedRandomSource(0L);
        }
        throw new IllegalArgumentException();
    }

    public static void derive(PositionalRandomFactory deriver, RandomSource random, int x, int y, int z) {
        if (deriver instanceof XoroshiroPositionalRandomFactory factory) {
            final Xoroshiro128PlusPlus implementation = ((XoroshiroRandomSource) random).randomNumberGenerator;
            implementation.seedLo = (Mth.getSeed(x, y, z) ^ factory.seedLo);
            implementation.seedHi = (factory.seedHi);
            return;
        }
        if (deriver instanceof LegacyRandomSource.LegacyPositionalRandomFactory factory) {
            final SingleThreadedRandomSource random1 = (SingleThreadedRandomSource) random;
            random1.setSeed(Mth.getSeed(x, y, z) ^ factory.seed);
            return;
        }
        throw new IllegalArgumentException();
    }
}
