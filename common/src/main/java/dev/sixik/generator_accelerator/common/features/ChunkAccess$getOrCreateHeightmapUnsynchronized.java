package dev.sixik.generator_accelerator.common.features;

import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Интерфейс позволяющий получить или создать Heightmap напряму из {@link net.minecraft.world.level.chunk.ChunkAccess}
 */
public interface ChunkAccess$getOrCreateHeightmapUnsynchronized {

    /**
     * @return {@link Heightmap} из {@link net.minecraft.world.level.chunk.ChunkAccess#heightmaps}
     */
    Heightmap bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types types);
}
