package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.features;

import dev.sixik.moonrisegeneratoraccelerator.common.utils.pools.SimpleObjectPool;
import dev.sixik.moonrisegeneratoraccelerator.common.utils.pools.SimpleObjectPoolUnSynchronize;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.Optional;

public class PooledFeatureContext<FC extends FeatureConfiguration> extends FeaturePlaceContext<FC> {

    public static final ThreadLocal<SimpleObjectPool<PooledFeatureContext<?>>> POOL = ThreadLocal.withInitial(() ->
            new SimpleObjectPoolUnSynchronize<>(
                    unused -> new PooledFeatureContext<>(),
                    PooledFeatureContext::reInit,
                    PooledFeatureContext::reInit,
                    2048
            ));

    private Optional<ConfiguredFeature<?, ?>> topFeature;
    private WorldGenLevel level;
    private ChunkGenerator chunkGenerator;
    private RandomSource random;
    private BlockPos origin;
    private FC config;

    public PooledFeatureContext() {
        super(null, null, null, null, null, null);
    }

    public void reInit(Optional<ConfiguredFeature<?, ?>> feature,
                       WorldGenLevel level,
                       ChunkGenerator chunkGenerator,
                       RandomSource random,
                       BlockPos origin,
                       FC config) {
        this.topFeature = feature;
        this.level = level;
        this.chunkGenerator = chunkGenerator;
        this.random = random;
        this.origin = origin;
        this.config = config;
    }

    public void reInit() {
        this.topFeature = null;
        this.level = null;
        this.chunkGenerator = null;
        this.random = null;
        this.origin = null;
        this.config = null;
    }

    @Override
    public Optional<ConfiguredFeature<?, ?>> topFeature() {
        return this.topFeature;
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
    public FC config() {
        return this.config;
    }
}
