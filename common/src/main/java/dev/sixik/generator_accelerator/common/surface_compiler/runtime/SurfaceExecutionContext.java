package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import dev.sixik.generator_accelerator.common.surface_compiler.cow.SectionCowManager;
import dev.sixik.generator_accelerator.common.surface_compiler.snapshot.SurfaceReadSnapshot;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

public record SurfaceExecutionContext(
        SurfaceSystem surfaceSystem,
        RandomState randomState,
        BiomeManager biomeManager,
        Registry<Biome> biomeRegistry,
        boolean useLegacyRandomSource,
        WorldGenerationContext worldContext,
        ChunkAccess chunk,
        NoiseChunk noiseChunk,
        SurfaceRules.RuleSource ruleSource,
        SurfaceWorkerState workerState,
        SurfaceReadSnapshot snapshot,
        SectionCowManager cowManager
) {
}
