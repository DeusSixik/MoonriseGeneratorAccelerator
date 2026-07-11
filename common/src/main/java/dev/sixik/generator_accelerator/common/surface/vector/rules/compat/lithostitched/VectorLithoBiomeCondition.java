package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

public class VectorLithoBiomeCondition implements VectorCondition {
    private final HolderSet<Biome> allowedBiomes;

    public VectorLithoBiomeCondition(HolderSet<Biome> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                if (!this.allowedBiomes.contains(ctx.getBiome(i))) {
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
