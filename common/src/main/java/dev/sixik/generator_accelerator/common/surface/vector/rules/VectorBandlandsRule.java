package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorBandlandsRule implements VectorRule {

    public static final VectorBandlandsRule INSTANCE = new VectorBandlandsRule();

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {

        final SurfaceSystem surfaceSystem = ctx.surfaceSystem;

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int localY = (i >> 8) & 15;

                int globalX = ctx.sectionStartX + localX;
                int globalY = ctx.sectionStartY + localY;
                int globalZ = ctx.sectionStartZ + localZ;

                BlockState bandState = surfaceSystem.getBand(globalX, globalY, globalZ);
                rawBlockData[i] = GA$BlockStateExtension.get(bandState).bts$getFastId();
                word &= word - 1L;
            }
        }

        activeMask.clear();
    }
}
