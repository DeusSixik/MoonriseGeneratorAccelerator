package dev.sixik.density_compiller.compiler.wrappers;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class EndIslandHelper {

    private static final ThreadLocal<IslandCache> CACHE = ThreadLocal.withInitial(IslandCache::new);

    public static double fastCompute(DensityFunctions.EndIslandDensityFunction node, DensityFunction.FunctionContext ctx) {
        int x8 = ctx.blockX() / 8;
        int z8 = ctx.blockZ() / 8;

        IslandCache cache = CACHE.get();

        if (cache.lastX != x8 || cache.lastZ != z8 || cache.lastNode != node) {
            float heightValue = DensityFunctions.EndIslandDensityFunction.getHeightValue(node.islandNoise, x8, z8);

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
}
