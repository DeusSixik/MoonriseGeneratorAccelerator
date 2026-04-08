package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
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
        /*
            (Fast Path)
            If a rule only specifies 1 biome (90% of cases), we don't make any extra allocations.
         */
        if (this.targetBiomes.size() == 1) {
            BitSet biomeMask = ctx.getBiomeMask(this.targetBiomes.get(0));
            activeMask.and(biomeMask);
            return;
        }

        // (OR)
        BitSet combinedBiomeMask = new BitSet(4096);

        List<ResourceKey<Biome>> biomes = this.targetBiomes;
        for (int i = 0; i < biomes.size(); i++) {
            // We take a mask of a separate biome (instantly from the context cache)
            BitSet singleBiomeMask = ctx.getBiomeMask(biomes.get(i));

            // If a block was '1' in at least one of the biomes, it will become '1' in combinedBiomeMask
            combinedBiomeMask.or(singleBiomeMask);
        }

        // Cutting off the leftovers
        activeMask.and(combinedBiomeMask);
    }
}
