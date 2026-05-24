package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public class FastBlockStateCache {

    private volatile static boolean initialized;

    public static BlockState[] STATES;
    public static int[] BLOCK_BY_STATE;
    public static FluidState[] FLUID_STATES;
    public static byte[] FLUID_KIND_BY_STATE;

    public static boolean[] AIR_STATES;
    public static boolean[] EMPTY_STATES;
    public static boolean[] RANDOM_TICKING_BLOCK_STATES;
    public static boolean[] FLUID_EMPTY_STATES;
    public static boolean[] RANDOM_TICKING_FLUID_STATES;
    public static boolean[] LIGHT_EMITTING_STATES;
    public static boolean[] IS_BLOCK_MOTION_STATES;
    public static boolean[] IS_BLOCK_MOTION_BLOCKING_STATES;
    public static boolean[] IS_FLUID_STATES_EMPTY;
    public static int[] TAG_MASK;

    public static final int TAG_STONE_ORE_REPLACEABLES = 1 << 0;
    public static final int TAG_DEEPSLATE_ORE_REPLACEABLES = 1 << 1;
    public static final int TAG_DIRT = 1 << 2;
    public static final int TAG_NYLIUM = 1 << 3;
    public static final int BLOCK_LEAVES = 1 << 4;

    public static final byte FLUID_KIND_EMPTY = 0;
    public static final byte FLUID_KIND_WATER = 1;
    public static final byte FLUID_KIND_LAVA = 2;
    public static final byte FLUID_KIND_OTHER = 3;

    public static int STONE_ID;
    public static int DIRT_ID;
    public static int GRASS_BLOCK_ID;
    public static int COARSE_DIRT_ID;
    public static int PODZOL_ID;
    public static int MYCELIUM_ID;
    public static int ROOTED_DIRT_ID;
    public static int MUD_ID;
    public static int CLAY_ID;
    public static int FARMLAND_ID;
    public static int DEEPSLATE_ID;
    public static int GRANITE_ID;
    public static int DIORITE_ID;
    public static int ANDESITE_ID;
    public static int TUFF_ID;
    public static int CALCITE_ID;
    public static int NETHERRACK_ID;
    public static int BASALT_ID;
    public static int BLACKSTONE_ID;
    public static int END_STONE_ID;
    public static int CAVE_AIR_ID;
    public static int VOID_AIR_ID;
    public static int SHORT_GRASS_ID;
    public static int TALL_GRASS_ID;
    public static int FERN_ID;
    public static int LARGE_FERN_ID;
    public static int SEAGRASS_ID;
    public static int TALL_SEAGRASS_ID;
    public static int MOSS_BLOCK_ID;

    private static int size;

    public static void init(GeneratorAccelerator.Platform platform) {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        if (registrySize <= 1) {
            return;
        }

        if (initialized && size == registrySize && STATES != null && AIR_STATES != null
                && BLOCK_BY_STATE != null && FLUID_STATES != null && FLUID_KIND_BY_STATE != null
                && EMPTY_STATES != null && RANDOM_TICKING_BLOCK_STATES != null
                && FLUID_EMPTY_STATES != null && RANDOM_TICKING_FLUID_STATES != null
                && LIGHT_EMITTING_STATES != null
                && IS_BLOCK_MOTION_STATES != null
                && IS_BLOCK_MOTION_BLOCKING_STATES != null) {
            return;
        }
        synchronized (FastBlockStateCache.class) {
            registrySize = Block.BLOCK_STATE_REGISTRY.size();
            if (registrySize <= 1) {
                return;
            }
            if (initialized && size == registrySize && STATES != null && AIR_STATES != null
                    && BLOCK_BY_STATE != null && FLUID_STATES != null && FLUID_KIND_BY_STATE != null
                    && EMPTY_STATES != null && RANDOM_TICKING_BLOCK_STATES != null
                    && FLUID_EMPTY_STATES != null && RANDOM_TICKING_FLUID_STATES != null
                    && LIGHT_EMITTING_STATES != null
                    && IS_BLOCK_MOTION_STATES != null
                    && IS_BLOCK_MOTION_BLOCKING_STATES != null) {
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
            int[] blocks = new int[capacity];
            FluidState[] fluidStates = new FluidState[capacity];
            byte[] fluidKinds = new byte[capacity];
            boolean[] airStates = new boolean[capacity];
            boolean[] emptyStates = new boolean[capacity];
            boolean[] randomTickingBlockStates = new boolean[capacity];
            boolean[] fluidEmptyStates = new boolean[capacity];
            boolean[] randomTickingFluidStates = new boolean[capacity];
            boolean[] lightEmittingStates = new boolean[capacity];
            boolean[] isBlocksMotion = new boolean[capacity];
            boolean[] isBlockMotionBlocking = new boolean[capacity];
            boolean[] isFluidStatesEmpty = new boolean[capacity];

            for (int i = 0; i < capacity; i++) {
                states[i] = air;
                fluidStates[i] = air.getFluidState();
                isFluidStatesEmpty[i] = air.getFluidState().isEmpty();
                airStates[i] = true;
                emptyStates[i] = true;
                fluidEmptyStates[i] = true;
                fluidKinds[i] = FLUID_KIND_EMPTY;
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                int blockId = BuiltInRegistries.BLOCK.getId(block);
                for (BlockState possibleState : block.getStateDefinition().getPossibleStates()) {
                    int fastId = Block.getId(possibleState);
                    if (fastId < 0 || fastId >= capacity) {
                        continue;
                    }
                    FluidState fluidState = possibleState.getFluidState();
                    states[fastId] = possibleState;
                    blocks[fastId] =  blockId;
                    fluidStates[fastId] = fluidState;
                    fluidKinds[fastId] = fluidKindFor(fluidState);
                    airStates[fastId] = possibleState.isAir();
                    emptyStates[fastId] = possibleState.isAir();
                    randomTickingBlockStates[fastId] = possibleState.isRandomlyTicking();
                    fluidEmptyStates[fastId] = fluidState.isEmpty();
                    randomTickingFluidStates[fastId] = !fluidEmptyStates[fastId] && fluidState.isRandomlyTicking();
                    lightEmittingStates[fastId] = possibleState.getLightEmission() != 0;
                    isBlocksMotion[fastId] = possibleState.blocksMotion();
                    isBlockMotionBlocking[fastId] = possibleState.blocksMotion() || !possibleState.getFluidState().isEmpty();
                    GA$BlockStateExtension.get(possibleState).bts$setFastId(fastId);
                }
            }



            STATES = states;
            BLOCK_BY_STATE = blocks;
            FLUID_STATES = fluidStates;
            FLUID_KIND_BY_STATE = fluidKinds;
            AIR_STATES = airStates;
            EMPTY_STATES = emptyStates;
            RANDOM_TICKING_BLOCK_STATES = randomTickingBlockStates;
            FLUID_EMPTY_STATES = fluidEmptyStates;
            RANDOM_TICKING_FLUID_STATES = randomTickingFluidStates;
            LIGHT_EMITTING_STATES = lightEmittingStates;
            IS_BLOCK_MOTION_STATES = isBlocksMotion;
            IS_BLOCK_MOTION_BLOCKING_STATES = isBlockMotionBlocking;
            IS_FLUID_STATES_EMPTY = isFluidStatesEmpty;
            size = registrySize;
            initBlockIds();

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

    public static FluidState getFluidState(int id) {
        if (id < 0) {
            return Fluids.EMPTY.defaultFluidState();
        }
        FluidState[] fluidStates = FLUID_STATES;
        if (fluidStates == null) {
            init(GeneratorAccelerator.platform);
            fluidStates = FLUID_STATES;
        }
        if (fluidStates == null || id >= fluidStates.length) {
            return Block.stateById(id).getFluidState();
        }
        return fluidStates[id];
    }

    public static byte fluidKind(int id) {
        if (id < 0) {
            return FLUID_KIND_EMPTY;
        }
        byte[] fluidKinds = FLUID_KIND_BY_STATE;
        if (fluidKinds == null) {
            init(GeneratorAccelerator.platform);
            fluidKinds = FLUID_KIND_BY_STATE;
        }
        if (fluidKinds == null || id >= fluidKinds.length) {
            return fluidKindFor(Block.stateById(id).getFluidState());
        }
        return fluidKinds[id];
    }

    public static boolean isWaterFluid(int id) {
        return fluidKind(id) == FLUID_KIND_WATER;
    }

    public static boolean isLavaFluid(int id) {
        return fluidKind(id) == FLUID_KIND_LAVA;
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

    public static boolean isBlockMotion(int id) {
        if (id < 0) {
            return false;
        }
        boolean[] isBlocksMotion = IS_BLOCK_MOTION_STATES;
        if (isBlocksMotion == null) {
            init(GeneratorAccelerator.platform);
            isBlocksMotion = IS_BLOCK_MOTION_STATES;
        }
        if (isBlocksMotion == null || id >= isBlocksMotion.length) {
            return Block.stateById(id).blocksMotion();
        }
        return isBlocksMotion[id];
    }

    public static boolean isBlockMotionBlocking(int id) {
        if (id < 0) {
            return false;
        }
        boolean[] isBlockMotionBlocking = IS_BLOCK_MOTION_BLOCKING_STATES;
        if (isBlockMotionBlocking == null) {
            init(GeneratorAccelerator.platform);
            isBlockMotionBlocking = IS_BLOCK_MOTION_BLOCKING_STATES;
        }
        if (isBlockMotionBlocking == null || id >= isBlockMotionBlocking.length) {
            return Block.stateById(id).blocksMotion() || !Block.stateById(id).getFluidState().isEmpty();
        }
        return isBlockMotionBlocking[id];
    }

    public static boolean hasTag(int id, int tag) {
        int[] mask = TAG_MASK;
        if(mask == null || id >= mask.length) {
            return false;
        }

        return (mask[id] & tag) != 0;
    }

    public static void reloadTags() {
        if(STATES == null) return;

        int[] newTagMask = new int[STATES.length];

        for (int i = 0; i < STATES.length; i++) {
            BlockState state = STATES[i];
            if(state == null || state.isAir()) continue;

            int flags = 0;

            if(state.is(BlockTags.STONE_ORE_REPLACEABLES))
                flags |= TAG_STONE_ORE_REPLACEABLES;
            if(state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES))
                flags |= TAG_DEEPSLATE_ORE_REPLACEABLES;
            if(state.is(BlockTags.DIRT))
                flags |= TAG_DIRT;
            if(state.is(BlockTags.NYLIUM))
                flags |= TAG_NYLIUM;
            if(state.getBlock() instanceof LeavesBlock)
                flags |= BLOCK_LEAVES;

            newTagMask[i] = flags;
        }

        TAG_MASK = newTagMask;
        GeneratorAccelerator.LOGGER.info("FastBlockStateCache: Tags reloaded into bitmasks. Size: {}", TAG_MASK.length);
    }

    private static void ensureInitialized() {
        if (STATES == null || BLOCK_BY_STATE == null || FLUID_STATES == null || FLUID_KIND_BY_STATE == null
                || AIR_STATES == null || EMPTY_STATES == null
                || RANDOM_TICKING_BLOCK_STATES == null || FLUID_EMPTY_STATES == null
                || RANDOM_TICKING_FLUID_STATES == null || LIGHT_EMITTING_STATES == null
                || size != Block.BLOCK_STATE_REGISTRY.size()) {
            init(GeneratorAccelerator.platform);
        }
    }

    private static byte fluidKindFor(FluidState fluidState) {
        if (fluidState == null || fluidState.isEmpty()) {
            return FLUID_KIND_EMPTY;
        }
        if (fluidState.getType() == Fluids.WATER) {
            return FLUID_KIND_WATER;
        }
        if (fluidState.getType() == Fluids.LAVA) {
            return FLUID_KIND_LAVA;
        }
        return FLUID_KIND_OTHER;
    }

    private static void initBlockIds() {
        STONE_ID = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        DIRT_ID = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        GRASS_BLOCK_ID = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        COARSE_DIRT_ID = BuiltInRegistries.BLOCK.getId(Blocks.COARSE_DIRT);
        PODZOL_ID = BuiltInRegistries.BLOCK.getId(Blocks.PODZOL);
        MYCELIUM_ID = BuiltInRegistries.BLOCK.getId(Blocks.MYCELIUM);
        ROOTED_DIRT_ID = BuiltInRegistries.BLOCK.getId(Blocks.ROOTED_DIRT);
        MUD_ID = BuiltInRegistries.BLOCK.getId(Blocks.MUD);
        CLAY_ID = BuiltInRegistries.BLOCK.getId(Blocks.CLAY);
        FARMLAND_ID = BuiltInRegistries.BLOCK.getId(Blocks.FARMLAND);
        DEEPSLATE_ID = BuiltInRegistries.BLOCK.getId(Blocks.DEEPSLATE);
        GRANITE_ID = BuiltInRegistries.BLOCK.getId(Blocks.GRANITE);
        DIORITE_ID = BuiltInRegistries.BLOCK.getId(Blocks.DIORITE);
        ANDESITE_ID = BuiltInRegistries.BLOCK.getId(Blocks.ANDESITE);
        TUFF_ID = BuiltInRegistries.BLOCK.getId(Blocks.TUFF);
        CALCITE_ID = BuiltInRegistries.BLOCK.getId(Blocks.CALCITE);
        NETHERRACK_ID = BuiltInRegistries.BLOCK.getId(Blocks.NETHERRACK);
        BASALT_ID = BuiltInRegistries.BLOCK.getId(Blocks.BASALT);
        BLACKSTONE_ID = BuiltInRegistries.BLOCK.getId(Blocks.BLACKSTONE);
        END_STONE_ID = BuiltInRegistries.BLOCK.getId(Blocks.END_STONE);
        CAVE_AIR_ID = BuiltInRegistries.BLOCK.getId(Blocks.CAVE_AIR);
        VOID_AIR_ID = BuiltInRegistries.BLOCK.getId(Blocks.VOID_AIR);
        SHORT_GRASS_ID = BuiltInRegistries.BLOCK.getId(Blocks.SHORT_GRASS);
        TALL_GRASS_ID = BuiltInRegistries.BLOCK.getId(Blocks.TALL_GRASS);
        FERN_ID = BuiltInRegistries.BLOCK.getId(Blocks.FERN);
        LARGE_FERN_ID = BuiltInRegistries.BLOCK.getId(Blocks.LARGE_FERN);
        SEAGRASS_ID = BuiltInRegistries.BLOCK.getId(Blocks.SEAGRASS);
        TALL_SEAGRASS_ID = BuiltInRegistries.BLOCK.getId(Blocks.TALL_SEAGRASS);
        MOSS_BLOCK_ID = BuiltInRegistries.BLOCK.getId(Blocks.MOSS_BLOCK);
    }
}
