package dev.sixik.generator_accelerator.common.worldgen.region;

import dev.sixik.generator_accelerator.common.noise.GAUnifiedRegionPacketAccess;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinNoiseBasedChunkGeneratorAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.chunk.status.WorldGenContext;

/**
 * Early NOISE-stage regional prewarm orchestration shared by the graph scheduler and pipeline.
 */
public final class GARegionalNoiseStagePrewarm {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.region.noiseStagePrewarm.enabled",
            // Creating NoiseChunk eagerly on every runnable NOISE node front-loads work and
            // regressed chunk throughput in practice, so keep the scheduler hook opt-in.
            "false"
    ));

    private GARegionalNoiseStagePrewarm() {
    }

    public static void prewarm(WorldGenContext context, ChunkAccess chunk) {
        if (!ENABLED || context == null || chunk == null) {
            return;
        }
        ChunkGenerator generator = context.generator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            return;
        }
        RandomState randomState = context.level().getChunkSource().randomState();
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(candidate ->
                ((MixinNoiseBasedChunkGeneratorAccessor) (Object) noiseGenerator).ga$invokeCreateNoiseChunk(
                        candidate,
                        context.level().structureManager(),
                        Blender.empty(),
                        randomState
                )
        );
        if (!(noiseChunk instanceof GAUnifiedRegionPacketAccess access)) {
            return;
        }
        access.ga$requestRegionalNoisePrewarm();
    }
}
