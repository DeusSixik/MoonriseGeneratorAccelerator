package dev.sixik.generator_accelerator.common.biome.region;

import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;

public final class GARegionalClimateQuartRasterOwner {
    private final Object biomeIdentity;
    private final BiomeManager.NoiseBiomeSource biomeSource;
    private final Object climateIdentity;
    private final Climate.Sampler climateSampler;
    private final int minQuartY;
    private final int quartHeight;
    private final int hash;

    public GARegionalClimateQuartRasterOwner(
            Object biomeIdentity,
            BiomeManager.NoiseBiomeSource biomeSource,
            Object climateIdentity,
            Climate.Sampler climateSampler,
            int minQuartY,
            int quartHeight
    ) {
        this.biomeIdentity = biomeIdentity;
        this.biomeSource = biomeSource;
        this.climateIdentity = climateIdentity;
        this.climateSampler = climateSampler;
        this.minQuartY = minQuartY;
        this.quartHeight = quartHeight;

        int result = System.identityHashCode(biomeIdentity);
        result = 31 * result + System.identityHashCode(climateIdentity);
        result = 31 * result + minQuartY;
        result = 31 * result + quartHeight;
        this.hash = result;
    }

    public Object biomeIdentity() {
        return this.biomeIdentity;
    }

    public BiomeManager.NoiseBiomeSource biomeSource() {
        return this.biomeSource;
    }

    public Object climateIdentity() {
        return this.climateIdentity;
    }

    public Climate.Sampler climateSampler() {
        return this.climateSampler;
    }

    public int minQuartY() {
        return this.minQuartY;
    }

    public int quartHeight() {
        return this.quartHeight;
    }

    public boolean hasClimate() {
        return this.climateSampler != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GARegionalClimateQuartRasterOwner that)) {
            return false;
        }
        return this.biomeIdentity == that.biomeIdentity
                && this.climateIdentity == that.climateIdentity
                && this.minQuartY == that.minQuartY
                && this.quartHeight == that.quartHeight;
    }

    @Override
    public int hashCode() {
        return this.hash;
    }
}
