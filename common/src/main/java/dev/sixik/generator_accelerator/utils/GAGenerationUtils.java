package dev.sixik.generator_accelerator.utils;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.*;
import org.jetbrains.annotations.NotNull;

public final class GAGenerationUtils {

    public static @NotNull RandomSource getRandom(PositionalRandomFactory deriver) {
        if (deriver instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory) {
            return new XoroshiroRandomSource(0L, 0L);
        }
        if (deriver instanceof LegacyRandomSource.LegacyPositionalRandomFactory) {
            return new SingleThreadedRandomSource(0L);
        }
        throw new IllegalArgumentException();
    }

    public static void derive(PositionalRandomFactory deriver, RandomSource random, int x, int y, int z) {
        if (deriver instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory factory) {
            final Xoroshiro128PlusPlus implementation = ((XoroshiroRandomSource) random).randomNumberGenerator;
            implementation.seedLo = (Mth.getSeed(x, y, z) ^ factory.seedLo);
            implementation.seedHi = (factory.seedHi);
            return;
        }
        if (deriver instanceof LegacyRandomSource.LegacyPositionalRandomFactory factory) {
            random.setSeed(Mth.getSeed(x, y, z) ^ factory.seed);
            return;
        }
        throw new IllegalArgumentException();
    }
}
