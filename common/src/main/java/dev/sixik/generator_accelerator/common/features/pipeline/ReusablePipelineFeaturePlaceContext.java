package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.Optional;

final class ReusablePipelineFeaturePlaceContext extends FeaturePlaceContext<FeatureConfiguration> {
    private WorldGenLevel level;
    private ChunkGenerator chunkGenerator;
    private RandomSource random;
    private BlockPos origin;
    private FeatureConfiguration config;

    ReusablePipelineFeaturePlaceContext() {
        super(Optional.empty(), null, null, null, BlockPos.ZERO, FeatureConfiguration.NONE);
    }

    ReusablePipelineFeaturePlaceContext set(
            WorldGenLevel level,
            ChunkGenerator chunkGenerator,
            RandomSource random,
            BlockPos origin,
            FeatureConfiguration config
    ) {
        this.level = level;
        this.chunkGenerator = chunkGenerator;
        this.random = random;
        this.origin = origin;
        this.config = config;
        return this;
    }

    void clear() {
        this.level = null;
        this.chunkGenerator = null;
        this.random = null;
        this.origin = BlockPos.ZERO;
        this.config = FeatureConfiguration.NONE;
    }

    @Override
    public Optional<ConfiguredFeature<?, ?>> topFeature() {
        return Optional.empty();
    }

    @Override
    public WorldGenLevel level() {
        return this.level;
    }

    @Override
    public ChunkGenerator chunkGenerator() {
        return this.chunkGenerator;
    }

    @Override
    public RandomSource random() {
        return this.random;
    }

    @Override
    public BlockPos origin() {
        return this.origin;
    }

    @Override
    public FeatureConfiguration config() {
        return this.config;
    }
}
