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
    private SectionDescriptorCache descriptors;

    PipelinePlacementContext(WorldGenLevel level, ChunkGenerator generator) {
        super(level, generator, Optional.empty());
        this.level = level;
        this.generator = generator;
        this.topFeature = Optional.empty();
        this.descriptors = null;
    }

    PipelinePlacementContext set(WorldGenLevel level, ChunkGenerator generator, Optional<PlacedFeature> feature, SectionDescriptorCache descriptors) {
        this.level = level;
        this.generator = generator;
        this.topFeature = feature;
        this.descriptors = descriptors;
        return this;
    }

    PipelinePlacementContext clearTopFeature() {
        this.topFeature = Optional.empty();
        return this;
    }

    @Override
    public int getHeight(Heightmap.Types types, int x, int z) {
        if (this.descriptors != null) {
            int descriptorHeight = this.descriptors.firstAvailableHeight(x >> 4, z >> 4, types, x & 15, z & 15);
            if (descriptorHeight != Integer.MIN_VALUE) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_HEIGHTMAP_HITS);
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.DESCRIPTOR_WORLD_READS_AVOIDED);
                return descriptorHeight;
            }
        }
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
