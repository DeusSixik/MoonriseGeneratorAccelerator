package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.world.level.block.state.BlockState;

public interface GA$BlockStateExtension {

    static GA$BlockStateExtension get(BlockState state) {
        return (GA$BlockStateExtension) state;
    }

    /**
     * Индекс для быстрого доступа к блоку в кэше
     */
    int bts$getFastId();

    void bts$setFastId(int id);
}
