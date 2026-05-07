package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Function;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator$optimize_apply_carvers {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private NoiseChunk createNoiseChunk(ChunkAccess chunkAccess, StructureManager structureManager, Blender blender, RandomState randomState) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Reuse chunk-owned positions and shared lookups instead of rebuilding them for every neighbor during carver dispatch.
     */
    @Overwrite
    public void applyCarvers(
            WorldGenRegion worldGenRegion,
            long seed,
            RandomState randomState,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunkAccess,
            GenerationStep.Carving carving
    ) {
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) (Object) this;
        BiomeSource biomeSource = generator.getBiomeSource();
        BiomeManager biomeManagerWithSource = biomeManager.withDifferentSource((x, y, z) -> biomeSource.getNoiseBiome(x, y, z, randomState.sampler()));
        Function<net.minecraft.core.BlockPos, Holder<Biome>> biomeGetter = biomeManagerWithSource::getBiome;
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        ChunkPos centerPos = chunkAccess.getPos();
        Blender blender = Blender.of(worldGenRegion);
        RegistryAccess registryAccess = worldGenRegion.registryAccess();
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk(chunk -> this.createNoiseChunk(chunk, structureManager, blender, randomState));
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext carvingContext = new CarvingContext(
                (NoiseBasedChunkGenerator) (Object) this,
                registryAccess,
                chunkAccess.getHeightAccessorForGeneration(),
                noiseChunk,
                randomState,
                this.settings.value().surfaceRule()
        );
        CarvingMask carvingMask = ((ProtoChunk) chunkAccess).getOrCreateCarvingMask(carving);

        for (int offsetX = -8; offsetX <= 8; ++offsetX) {
            for (int offsetZ = -8; offsetZ <= 8; ++offsetZ) {
                ChunkAccess neighborChunk = worldGenRegion.getChunk(centerPos.x + offsetX, centerPos.z + offsetZ);
                ChunkPos neighborPos = neighborChunk.getPos();
                int quartX = QuartPos.fromBlock(neighborPos.getMinBlockX());
                int quartZ = QuartPos.fromBlock(neighborPos.getMinBlockZ());
                BiomeGenerationSettings generationSettings = neighborChunk.carverBiome(
                        () -> generator.getBiomeGenerationSettings(biomeSource.getNoiseBiome(quartX, 0, quartZ, randomState.sampler()))
                );

                int index = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : generationSettings.getCarvers(carving)) {
                    ConfiguredWorldCarver<?> configuredWorldCarver = holder.value();
                    worldgenRandom.setLargeFeatureSeed(seed + index, neighborPos.x, neighborPos.z);
                    if (configuredWorldCarver.isStartChunk(worldgenRandom)) {
                        configuredWorldCarver.carve(
                                carvingContext,
                                chunkAccess,
                                biomeGetter,
                                worldgenRandom,
                                aquifer,
                                neighborPos,
                                carvingMask
                        );
                    }
                    ++index;
                }
            }
        }
    }
}
