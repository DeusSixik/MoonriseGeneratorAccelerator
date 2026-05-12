package dev.sixik.generator_accelerator.common.features.compat.biomeswevegone;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.BasaltBarreraExtension;
import net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.CragGardenExtension;

import java.util.function.Function;

public final class GABWGExtensions {
    private static final ThreadLocal<GABWGBiomeCache> BIOME_CACHE =
            ThreadLocal.withInitial(GABWGBiomeCache::new);

    private GABWGExtensions() {
    }

    public static void runExtensions(
            WorldGenContext worldGenContext,
            ChunkAccess chunk,
            ServerLevel serverLevel,
            WorldGenRegion worldGenRegion
    ) {
        Function<net.minecraft.core.BlockPos, Holder<Biome>> biomeGetter = BIOME_CACHE.get().reset(
                worldGenContext.generator(),
                worldGenContext.level().getChunkSource().randomState().sampler(),
                serverLevel.getSeed()
        );
        var noiseRegistry = worldGenRegion.registryAccess().registryOrThrow(Registries.NOISE);

        CragGardenExtension.runCragGardenExtension(
                biomeGetter,
                chunk,
                serverLevel.getSeed(),
                noiseRegistry.getOrThrow(Noises.SURFACE),
                noiseRegistry.getOrThrow(Noises.SURFACE_SECONDARY)
        );
        BasaltBarreraExtension.runBasaltBarreraExtension(
                biomeGetter,
                chunk,
                worldGenRegion,
                worldGenContext.generator()
        );
    }
}
