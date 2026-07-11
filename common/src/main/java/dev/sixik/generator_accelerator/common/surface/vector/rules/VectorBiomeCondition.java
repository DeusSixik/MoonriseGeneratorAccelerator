package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;
import java.util.List;

public class VectorBiomeCondition implements VectorCondition {
    private final List<ResourceKey<Biome>> targetBiomes;

    public VectorBiomeCondition(List<ResourceKey<Biome>> targetBiomes) {
        this.targetBiomes = targetBiomes;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);

                Holder<Biome> biome = ctx.getBiome(i);

                boolean matches = false;
                for (int j = 0; j < targetBiomes.size(); j++) {
                    if (biome.is(targetBiomes.get(j))) {
                        matches = true;
                        break;
                    }
                }

                if (!matches) {
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
