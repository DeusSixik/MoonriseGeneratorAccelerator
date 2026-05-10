package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.BitSet;

public class VectorTemperatureCondition implements VectorCondition {
    public static final VectorTemperatureCondition INSTANCE = new VectorTemperatureCondition();

    private VectorTemperatureCondition() {}

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        BlockPos.MutableBlockPos pos = ctx.mutablePos;

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            Holder<Biome> biome = ctx.surfaceBiomes[i & 255];

            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int localY = i >> 8;

            pos.set(ctx.sectionStartX + localX, ctx.sectionStartY + localY, ctx.sectionStartZ + localZ);

            if (!biome.value().coldEnoughToSnow(pos)) {
                activeMask.clear(i);
            }
        }
    }
}
