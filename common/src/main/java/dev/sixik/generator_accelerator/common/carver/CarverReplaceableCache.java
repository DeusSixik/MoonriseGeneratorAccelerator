package dev.sixik.generator_accelerator.common.carver;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;

import java.util.concurrent.ConcurrentHashMap;

public final class CarverReplaceableCache {

    private static final ConcurrentHashMap<CarverConfiguration, boolean[]> CACHE = new ConcurrentHashMap<>();

    private CarverReplaceableCache() {
    }

    public static boolean[] get(CarverConfiguration configuration) {
        return CACHE.computeIfAbsent(configuration, CarverReplaceableCache::build);
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
