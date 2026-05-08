package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.Optional;

public final class PipelinePlacementContext extends PlacementContext {
    private WorldGenLevel level;
    private ChunkGenerator generator;
    private Optional<PlacedFeature> topFeature;

    PipelinePlacementContext(WorldGenLevel level, ChunkGenerator generator) {
        super(level, generator, Optional.empty());
        this.level = level;
        this.generator = generator;
        this.topFeature = Optional.empty();
    }

    PipelinePlacementContext set(WorldGenLevel level, ChunkGenerator generator, Optional<PlacedFeature> feature) {
        this.level = level;
        this.generator = generator;
        this.topFeature = feature;
        return this;
    }

    PipelinePlacementContext clearTopFeature() {
        this.topFeature = Optional.empty();
        return this;
    }

    @Override
    public int getHeight(Heightmap.Types types, int x, int z) {
        return this.level.getHeight(types, x, z);
    }

    @Override
    public CarvingMask getCarvingMask(ChunkPos chunkPos, GenerationStep.Carving carving) {
        return ((ProtoChunk) this.level.getChunk(chunkPos.x, chunkPos.z)).getOrCreateCarvingMask(carving);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.WORLD_BLOCK_READS);
        return this.level.getBlockState(pos);
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }

    @Override
    public WorldGenLevel getLevel() {
        return this.level;
    }

    @Override
    public Optional<PlacedFeature> topFeature() {
        return this.topFeature;
    }

    @Override
    public ChunkGenerator generator() {
        return this.generator;
    }
}
