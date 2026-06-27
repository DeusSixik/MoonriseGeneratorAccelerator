package dev.sixik.generator_accelerator.common.density.compiler.compiler;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Rebuilds marker wrappers through the public DensityFunctions factories.
 *
 * <p>NeoForge/Minecraft patch levels differ on the visibility of
 * {@link DensityFunctions.Marker}'s constructor. Calling the public factory
 * methods keeps the DensityCompiler binary-compatible across those variants
 * while preserving the exact marker type NoiseChunk expects.
 */
public final class MarkerRewriter {

    private MarkerRewriter() {}

    public static DensityFunction rebuild(DensityFunctions.Marker.Type type, DensityFunction wrapped) {
        return switch (type) {
            case Interpolated -> DensityFunctions.interpolated(wrapped);
            case FlatCache -> DensityFunctions.flatCache(wrapped);
            case Cache2D -> DensityFunctions.cache2d(wrapped);
            case CacheOnce -> DensityFunctions.cacheOnce(wrapped);
            case CacheAllInCell -> DensityFunctions.cacheAllInCell(wrapped);
        };
    }
}
