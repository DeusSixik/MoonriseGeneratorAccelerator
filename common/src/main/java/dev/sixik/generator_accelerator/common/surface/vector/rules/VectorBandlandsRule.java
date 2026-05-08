package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.util.BitSet;

public class VectorBandlandsRule implements VectorRule {

    public static final VectorBandlandsRule INSTANCE = new VectorBandlandsRule();

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {

        final SurfaceSystem surfaceSystem = ctx.surfaceSystem;

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int localY = (i >> 8) & 15;

            int globalX = ctx.sectionStartX + localX;
            int globalY = ctx.sectionStartY + localY;
            int globalZ = ctx.sectionStartZ + localZ;

            BlockState bandState = surfaceSystem.getBand(globalX, globalY, globalZ);
            rawBlockData[i] = GA$BlockStateExtension.get(bandState).bts$getFastId();
        }

        activeMask.clear();
    }
}