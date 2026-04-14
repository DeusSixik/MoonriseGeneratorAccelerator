package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import java.util.BitSet;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.worldgen.lithostitched.worldgen.bandlands.Bandlands;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class VectorBandlandsRule implements VectorRule {
    private final Bandlands bandlands;
    public VectorBandlandsRule(Bandlands bandlands) {
        this.bandlands = bandlands;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int globalX = ctx.sectionStartX * 16 + localX;
            int globalZ = ctx.sectionStartZ * 16 + localZ;
            int y = ctx.surfaceHeights[i];

            BlockState state = this.bandlands.getBand(ctx.surfaceSystem, globalX, y, globalZ);

            if (state != null) {
                rawBlockData[i] = Block.getId(state);
                activeMask.clear(i);
            }
        }
    }
}
