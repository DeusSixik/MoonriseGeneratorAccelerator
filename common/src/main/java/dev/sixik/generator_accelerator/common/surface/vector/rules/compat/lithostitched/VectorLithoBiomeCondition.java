package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;

import java.util.BitSet;

public class VectorLithoBiomeCondition implements VectorCondition {
    private final HolderSet<Biome> allowedBiomes;

    public VectorLithoBiomeCondition(HolderSet<Biome> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            if (!this.allowedBiomes.contains(ctx.getBiome(i))) {
                activeMask.clear(i);
            }
        }
    }
}