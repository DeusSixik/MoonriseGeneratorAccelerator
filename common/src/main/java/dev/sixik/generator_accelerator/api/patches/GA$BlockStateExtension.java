package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public interface GA$BlockStateExtension {
    final class Fallback implements GA$BlockStateExtension {
        private final BlockState state;

        private Fallback(BlockState state) {
            this.state = state;
        }

        @Override
        public int bts$getFastId() {
            return Block.getId(this.state);
        }

        @Override
        public void bts$setFastId(int id) {
            // Plain unit tests do not apply the mixin-backed fast-id field.
        }
    }

    static GA$BlockStateExtension get(BlockState state) {
        if (state instanceof GA$BlockStateExtension extension) {
            return extension;
        }
        return new Fallback(state);
    }

    int bts$getFastId();

    void bts$setFastId(int id);
}