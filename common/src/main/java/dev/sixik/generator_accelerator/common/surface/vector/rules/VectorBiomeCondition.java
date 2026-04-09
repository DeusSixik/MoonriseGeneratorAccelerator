package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.BitSet;
import java.util.List;

public class VectorBiomeCondition implements VectorCondition {
    private final List<ResourceKey<Biome>> targetBiomes;

    public VectorBiomeCondition(List<ResourceKey<Biome>> targetBiomes) {
        this.targetBiomes = targetBiomes;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {

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
        }
    }
}
