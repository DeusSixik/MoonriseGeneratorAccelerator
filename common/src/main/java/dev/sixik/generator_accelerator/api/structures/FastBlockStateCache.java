package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class FastBlockStateCache {

    private volatile static boolean initialized;

    public static BlockState[] STATES;
    public static boolean[] AIR_STATES;
    private static int size;

    public static void init(GeneratorAccelerator.Platform platform) {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        if (initialized && size == registrySize && STATES != null && AIR_STATES != null) {
            return;
        }
        synchronized (FastBlockStateCache.class) {
            registrySize = Block.BLOCK_STATE_REGISTRY.size();
            if (initialized && size == registrySize && STATES != null && AIR_STATES != null) {
                return;
            }

            BlockState air = Blocks.AIR.defaultBlockState();
            int maxStateId = 0;
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState possibleState : block.getStateDefinition().getPossibleStates()) {
                    maxStateId = Math.max(maxStateId, Block.getId(possibleState));
                }
            }

            int capacity = Math.max(registrySize, maxStateId + 1);
            BlockState[] states = new BlockState[capacity];
            boolean[] airStates = new boolean[capacity];

            for (int i = 0; i < capacity; i++) {
                states[i] = air;
                airStates[i] = true;
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState possibleState : block.getStateDefinition().getPossibleStates()) {
                    int fastId = Block.getId(possibleState);
                    if (fastId < 0 || fastId >= capacity) {
                        continue;
                    }
                    states[fastId] = possibleState;
                    airStates[fastId] = possibleState.isAir();
                    GA$BlockStateExtension.get(possibleState).bts$setFastId(fastId);
                }
            }

            STATES = states;
            AIR_STATES = airStates;
            size = registrySize;

            GeneratorAccelerator.LOGGER.info("PLATFORM: {}", platform);
            GeneratorAccelerator.LOGGER.info("STATE REGISTRY SIZE: {}", size);
            GeneratorAccelerator.LOGGER.info("STATE CACHE CAPACITY: {}", capacity);

            initialized = true;
        }
    }

    public static BlockState getBlockState(int id) {
        ensureInitialized();
        if (id < 0) {
            return Blocks.AIR.defaultBlockState();
        }
        if (id >= STATES.length) {
            return Block.stateById(id);
        }

        return STATES[id];
    }

    public static boolean isAir(int id) {
        ensureInitialized();
        if (id < 0) {
            return true;
        }
        if (id >= AIR_STATES.length) {
            return Block.stateById(id).isAir();
        }

        return AIR_STATES[id];
    }

    private static void ensureInitialized() {
        if (STATES == null || AIR_STATES == null || size != Block.BLOCK_STATE_REGISTRY.size()) {
            init(GeneratorAccelerator.platform);
        }
    }
}
