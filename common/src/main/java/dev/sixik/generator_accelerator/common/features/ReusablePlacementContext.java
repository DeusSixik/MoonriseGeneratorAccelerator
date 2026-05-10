package dev.sixik.generator_accelerator.common.features;

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

public final class ReusablePlacementContext extends PlacementContext {
    private static final Optional<PlacedFeature> NO_TOP_FEATURE = Optional.empty();

    private WorldGenLevel level;
    private ChunkGenerator generator;

    public ReusablePlacementContext(WorldGenLevel level, ChunkGenerator generator) {
        super(level, generator, NO_TOP_FEATURE);
        this.level = level;
        this.generator = generator;
    }

    public void set(WorldGenLevel level, ChunkGenerator generator) {
        this.level = level;
        this.generator = generator;
    }

    public void clear() {
        this.level = null;
        this.generator = null;
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
        return NO_TOP_FEATURE;
    }

    @Override
    public ChunkGenerator generator() {
        return this.generator;
    }
}
