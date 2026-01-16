package dev.sixik.density_compiller.compiler.wrappers;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

public class EndIslandHelper {

    private static final ThreadLocal<IslandCache> CACHE = ThreadLocal.withInitial(IslandCache::new);

    public static double fastCompute(DensityFunctions.EndIslandDensityFunction node,
                                     DensityFunction.FunctionContext ctx) {
        int x8 = ctx.blockX() / 8;
        int z8 = ctx.blockZ() / 8;

        IslandCache cache = CACHE.get();

        if (cache.lastX != x8 || cache.lastZ != z8 || cache.lastNode != node) {
            float heightValue = getHeightValue(node.islandNoise, x8, z8);

            cache.lastValue = ((double) heightValue - 8.0) / 128.0;
            cache.lastX = x8;
            cache.lastZ = z8;
            cache.lastNode = node;
        }

        return cache.lastValue;
    }

    private static class IslandCache {
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        Object lastNode = null;
        double lastValue = 0;
    }

    public static float getHeightValue(SimplexNoise simplexNoise, int i, int j) {
        final int k = i / 2;
        final int l = j / 2;
        final int m = i % 2;
        final int n = j % 2;

        // Начальное значение f
        float f = 100.0f - Mth.sqrt((float)i * i + (float)j * j) * 8.0f;
        if (f < -100.0f) f = -100.0f; else if (f > 80.0f) f = 80.0f;

        final double threshold = (double) -0.9f;

        for (int o = -12; o <= 12; ++o) {
            final long q = (long) k + o;
            final long q2 = q * q;
            final float absQf = (float) (q < 0 ? -q : q); // Инлайним abs
            final float h = (float) (m - o * 2);
            final float h2 = h * h;

            for (int p = -12; p <= 12; ++p) {
                final long r = (long) l + p;
                final long r2 = r * r;

                // 1. Самая дешевая проверка — расстояние
                if (q2 + r2 <= 4096L) continue;

                // 2. Самая дорогая операция — шум. Выполняем строго после проверки дистанции.
                if (!(simplexNoise.getValue(q, r) < threshold)) continue;

                final float absRf = (float) (r < 0 ? -r : r);
                final float g = (absQf * 3439.0f + absRf * 147.0f) % 13.0f + 9.0f;

                final float s = (float) (n - p * 2);
                float t = 100.0f - Mth.sqrt(h2 + s * s) * g;

                // Инлайним clamp и max
                if (t < -100.0f) t = -100.0f; else if (t > 80.0f) t = 80.0f;
                if (t > f) f = t;
            }
        }
        return f;
    }
}
