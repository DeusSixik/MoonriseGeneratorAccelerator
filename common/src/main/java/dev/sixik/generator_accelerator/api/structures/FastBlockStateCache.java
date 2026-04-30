package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class FastBlockStateCache {

    public static BlockState[] STATES;
    private static int size;

    public static void init() {
        int maxId = Block.BLOCK_STATE_REGISTRY.size();
        STATES = new BlockState[maxId];

        for (int i = 0; i < maxId; i++) {
            final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);

            if(state == null) {
                STATES[i] = Blocks.AIR.defaultBlockState();
                continue;
            }
            STATES[i] = state;
            GA$BlockStateExtension.get(state).bts$setFastId(i);
        }
        size = STATES.length;
    }

    public static BlockState getBlockState(int id) {
        return STATES[id];
    }
}
