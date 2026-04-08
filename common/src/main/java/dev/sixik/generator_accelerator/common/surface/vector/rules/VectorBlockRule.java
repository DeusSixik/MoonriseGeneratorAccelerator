package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.BitSet;

public class VectorBlockRule implements VectorRule {
    private final char blockId;

    public VectorBlockRule(BlockState state) {
        this.blockId = (char) Block.getId(state);
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            rawBlockData[i] = this.blockId;
        }

        activeMask.clear();
    }
}