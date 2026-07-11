package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.worldgen.lithostitched.impl.worldgen.bandlands.Bandlands;
import net.minecraft.world.level.block.state.BlockState;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorBandlandsRule implements VectorRule {
    private final Bandlands bandlands;

    public VectorBandlandsRule(Bandlands bandlands) {
        this.bandlands = bandlands;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int globalX = ctx.sectionStartX * 16 + localX;
                int globalZ = ctx.sectionStartZ * 16 + localZ;
                int y = ctx.surfaceHeights[i];

                BlockState state = this.bandlands.getBand(ctx.surfaceSystem, globalX, y, globalZ);

                if (state != null) {
                    rawBlockData[i] = GA$BlockStateExtension.get(state).bts$getFastId();
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }
}
