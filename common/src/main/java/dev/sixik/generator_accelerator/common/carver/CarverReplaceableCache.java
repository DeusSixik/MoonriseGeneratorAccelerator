package dev.sixik.generator_accelerator.common.carver;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;

public final class CarverReplaceableCache {

    private static final LoadingCache<CarverConfiguration, boolean[]> CACHE = Caffeine.newBuilder()
            .initialCapacity(16)
            .weakKeys()
            .build(CarverReplaceableCache::build);

    private CarverReplaceableCache() {
    }

    public static boolean[] get(CarverConfiguration configuration) {
        return CACHE.get(configuration);
    }

    public static void clear() {
        CACHE.invalidateAll();
        CACHE.cleanUp();
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
