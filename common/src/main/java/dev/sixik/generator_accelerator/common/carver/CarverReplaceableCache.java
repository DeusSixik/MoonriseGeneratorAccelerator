package dev.sixik.generator_accelerator.common.carver;

import com.google.common.collect.MapMaker;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;

import java.util.concurrent.ConcurrentMap;

public final class CarverReplaceableCache {

    private static final ConcurrentMap<CarverConfiguration, boolean[]> CACHE =
            new MapMaker().weakKeys().concurrencyLevel(4).makeMap();

    private CarverReplaceableCache() {
    }

    public static boolean[] get(CarverConfiguration configuration) {
        return CACHE.computeIfAbsent(configuration, CarverReplaceableCache::build);
    }

    public static void clear() {
        CACHE.clear();
    }

    private static boolean[] build(CarverConfiguration configuration) {
        if (FastBlockStateCache.STATES == null) {
            FastBlockStateCache.getBlockState(0);
        }

        BlockState[] states = FastBlockStateCache.STATES;
        boolean[] replaceableStates = new boolean[states.length];
        for (int i = 0; i < states.length; i++) {
            Block block = states[i].getBlock();
            replaceableStates[i] = configuration.replaceable.contains(block.builtInRegistryHolder());
        }

        return replaceableStates;
    }
}
