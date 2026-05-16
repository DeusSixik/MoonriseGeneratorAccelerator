package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class FastBlockStateCache {

    private volatile static boolean initialized;

    public static BlockState[] STATES;
    public static boolean[] AIR_STATES;
    public static boolean[] EMPTY_STATES;
    public static boolean[] RANDOM_TICKING_BLOCK_STATES;
    public static boolean[] FLUID_EMPTY_STATES;
    public static boolean[] RANDOM_TICKING_FLUID_STATES;
    public static boolean[] LIGHT_EMITTING_STATES;
    private static int size;

    public static void init(GeneratorAccelerator.Platform platform) {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        if (registrySize <= 1) {
            return;
        }
        if (initialized && size == registrySize && STATES != null && AIR_STATES != null
                && EMPTY_STATES != null && RANDOM_TICKING_BLOCK_STATES != null
                && FLUID_EMPTY_STATES != null && RANDOM_TICKING_FLUID_STATES != null
                && LIGHT_EMITTING_STATES != null) {
            return;
        }
        synchronized (FastBlockStateCache.class) {
            registrySize = Block.BLOCK_STATE_REGISTRY.size();
            if (registrySize <= 1) {
                return;
            }
            if (initialized && size == registrySize && STATES != null && AIR_STATES != null
                    && EMPTY_STATES != null && RANDOM_TICKING_BLOCK_STATES != null
                    && FLUID_EMPTY_STATES != null && RANDOM_TICKING_FLUID_STATES != null
                    && LIGHT_EMITTING_STATES != null) {
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
            boolean[] emptyStates = new boolean[capacity];
            boolean[] randomTickingBlockStates = new boolean[capacity];
            boolean[] fluidEmptyStates = new boolean[capacity];
            boolean[] randomTickingFluidStates = new boolean[capacity];
            boolean[] lightEmittingStates = new boolean[capacity];

            for (int i = 0; i < capacity; i++) {
                states[i] = air;
                airStates[i] = true;
                emptyStates[i] = true;
                fluidEmptyStates[i] = true;
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState possibleState : block.getStateDefinition().getPossibleStates()) {
                    int fastId = Block.getId(possibleState);
                    if (fastId < 0 || fastId >= capacity) {
                        continue;
                    }
                    FluidState fluidState = possibleState.getFluidState();
                    states[fastId] = possibleState;
                    airStates[fastId] = possibleState.isAir();
                    emptyStates[fastId] = possibleState.isAir();
                    randomTickingBlockStates[fastId] = possibleState.isRandomlyTicking();
                    fluidEmptyStates[fastId] = fluidState.isEmpty();
                    randomTickingFluidStates[fastId] = !fluidEmptyStates[fastId] && fluidState.isRandomlyTicking();
                    lightEmittingStates[fastId] = possibleState.getLightEmission() != 0;
                    GA$BlockStateExtension.get(possibleState).bts$setFastId(fastId);
                }
            }

            STATES = states;
            AIR_STATES = airStates;
            EMPTY_STATES = emptyStates;
            RANDOM_TICKING_BLOCK_STATES = randomTickingBlockStates;
            FLUID_EMPTY_STATES = fluidEmptyStates;
            RANDOM_TICKING_FLUID_STATES = randomTickingFluidStates;
            LIGHT_EMITTING_STATES = lightEmittingStates;
            size = registrySize;

            GeneratorAccelerator.LOGGER.info("PLATFORM: {}", platform);
            GeneratorAccelerator.LOGGER.info("STATE REGISTRY SIZE: {}", size);
            GeneratorAccelerator.LOGGER.info("STATE CACHE CAPACITY: {}", capacity);

            initialized = true;
        }
    }

    public static BlockState getBlockState(int id) {
        if (id < 0) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState[] states = STATES;
        if (states == null) {
            init(GeneratorAccelerator.platform);
            states = STATES;
        }
        if (states == null || id >= states.length) {
            return Block.stateById(id);
        }

        return states[id];
    }

    public static boolean isAir(int id) {
        if (id < 0) {
            return true;
        }
        boolean[] airStates = AIR_STATES;
        if (airStates == null) {
            init(GeneratorAccelerator.platform);
            airStates = AIR_STATES;
        }
        if (airStates == null || id >= airStates.length) {
            return Block.stateById(id).isAir();
        }

        return airStates[id];
    }

    public static boolean isEmpty(int id) {
        if (id < 0) {
            return true;
        }
        boolean[] emptyStates = EMPTY_STATES;
        if (emptyStates == null) {
            init(GeneratorAccelerator.platform);
            emptyStates = EMPTY_STATES;
        }
        if (emptyStates == null || id >= emptyStates.length) {
            return Block.stateById(id).isAir();
        }

        return emptyStates[id];
    }

    public static boolean isRandomlyTickingBlock(int id) {
        if (id < 0) {
            return false;
        }
        boolean[] randomTickingBlockStates = RANDOM_TICKING_BLOCK_STATES;
        if (randomTickingBlockStates == null) {
            init(GeneratorAccelerator.platform);
            randomTickingBlockStates = RANDOM_TICKING_BLOCK_STATES;
        }
        if (randomTickingBlockStates == null || id >= randomTickingBlockStates.length) {
            return Block.stateById(id).isRandomlyTicking();
        }

        return randomTickingBlockStates[id];
    }

    public static boolean isFluidEmpty(int id) {
        if (id < 0) {
            return true;
        }
        boolean[] fluidEmptyStates = FLUID_EMPTY_STATES;
        if (fluidEmptyStates == null) {
            init(GeneratorAccelerator.platform);
            fluidEmptyStates = FLUID_EMPTY_STATES;
        }
        if (fluidEmptyStates == null || id >= fluidEmptyStates.length) {
            return Block.stateById(id).getFluidState().isEmpty();
        }

        return fluidEmptyStates[id];
    }

    public static boolean isRandomlyTickingFluid(int id) {
        if (id < 0) {
            return false;
        }
        boolean[] randomTickingFluidStates = RANDOM_TICKING_FLUID_STATES;
        if (randomTickingFluidStates == null) {
            init(GeneratorAccelerator.platform);
            randomTickingFluidStates = RANDOM_TICKING_FLUID_STATES;
        }
        if (randomTickingFluidStates == null || id >= randomTickingFluidStates.length) {
            FluidState fluidState = Block.stateById(id).getFluidState();
            return !fluidState.isEmpty() && fluidState.isRandomlyTicking();
        }

        return randomTickingFluidStates[id];
    }

    public static boolean hasLightEmission(int id) {
        if (id < 0) {
            return false;
        }
        boolean[] lightEmittingStates = LIGHT_EMITTING_STATES;
        if (lightEmittingStates == null) {
            init(GeneratorAccelerator.platform);
            lightEmittingStates = LIGHT_EMITTING_STATES;
        }
        if (lightEmittingStates == null || id >= lightEmittingStates.length) {
            return Block.stateById(id).getLightEmission() != 0;
        }

        return lightEmittingStates[id];
    }

    private static void ensureInitialized() {
        if (STATES == null || AIR_STATES == null || EMPTY_STATES == null
                || RANDOM_TICKING_BLOCK_STATES == null || FLUID_EMPTY_STATES == null
                || RANDOM_TICKING_FLUID_STATES == null || LIGHT_EMITTING_STATES == null
                || size != Block.BLOCK_STATE_REGISTRY.size()) {
            init(GeneratorAccelerator.platform);
        }
    }
}
