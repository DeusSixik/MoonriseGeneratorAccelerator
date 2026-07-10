package dev.sixik.generator_accelerator.common.surface_compiler.semantic;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

public record VanillaInvocationContext(
        SurfaceSystem surfaceSystem,
        RandomState randomState,
        BiomeManager biomeManager,
        Registry<Biome> biomeRegistry,
        boolean useLegacyRandomSource,
        WorldGenerationContext worldContext,
        ChunkAccess chunk,
        NoiseChunk noiseChunk,
        SurfaceRules.RuleSource ruleSource
) {
}
