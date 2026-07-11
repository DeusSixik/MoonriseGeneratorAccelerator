package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorBlockRule implements VectorRule {
    private final int blockId;

    public VectorBlockRule(BlockState state) {
        this.blockId = GA$BlockStateExtension.get(state).bts$getFastId();
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                rawBlockData[i] = this.blockId;
                word &= word - 1L;
            }
        }

        activeMask.clear();
    }
}
