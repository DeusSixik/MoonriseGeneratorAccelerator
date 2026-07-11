package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorTemperatureCondition implements VectorCondition {
    public static final VectorTemperatureCondition INSTANCE = new VectorTemperatureCondition();

    private VectorTemperatureCondition() {}

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        BlockPos.MutableBlockPos pos = ctx.mutablePos;

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                Holder<Biome> biome = ctx.surfaceBiomes[i & 255];

                int localX = i & 15;
                int localZ = (i >> 4) & 15;
                int localY = i >> 8;

                pos.set(ctx.sectionStartX + localX, ctx.sectionStartY + localY, ctx.sectionStartZ + localZ);

                if (!biome.value().coldEnoughToSnow(pos)) {
                    activeMask.clear(i);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requiredContext() {
        return VectorContextRequirements.SURFACE_BIOMES;
    }
}
