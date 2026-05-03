package dev.sixik.generator_accelerator.api.structures;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class FastBlockStateCache {

    private volatile static boolean initialized;

    public static BlockState[] STATES;
    private static int size;

    public static void init(GeneratorAccelerator.Platform platform) {
        if (initialized) return;
        synchronized (FastBlockStateCache.class) {
            if (initialized) return;

            if (platform != GeneratorAccelerator.Platform.NEOFORGE) {
                int maxId = Block.BLOCK_STATE_REGISTRY.size();
                STATES = new BlockState[maxId];

                for (int i = 0; i < maxId; i++) {
                    final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);

                    if (state == null) {
                        STATES[i] = Blocks.AIR.defaultBlockState();
                        continue;
                    }
                    STATES[i] = state;
                    GA$BlockStateExtension.get(state).bts$setFastId(i);
                }
            } else {
                List<BlockState> statesList = new ObjectArrayList<>();

                int i = 0;
                for (Block block : BuiltInRegistries.BLOCK) {
                    for (BlockState possibleState : block.getStateDefinition().getPossibleStates()) {

                        BlockState actualState = possibleState;

                        if (actualState != null) {
                            GA$BlockStateExtension.get(actualState).bts$setFastId(i);
                        } else {
                            actualState = Blocks.AIR.defaultBlockState();
                        }

                        statesList.add(actualState);
                        i++;
                    }
                }
                STATES = statesList.toArray(new BlockState[0]);
            }
            size = STATES.length;

            GeneratorAccelerator.LOGGER.info("PLATFORM: {}", platform);
            GeneratorAccelerator.LOGGER.info("STATES SIZE: {}", size);

            initialized = true;
        }
    }

    public static BlockState getBlockState(int id) {
        if (STATES == null)
            init(GeneratorAccelerator.platform);

        return STATES[id];
    }
}
