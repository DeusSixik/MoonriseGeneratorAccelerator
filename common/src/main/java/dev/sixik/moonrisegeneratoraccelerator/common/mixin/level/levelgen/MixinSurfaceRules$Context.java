package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.apache.commons.lang3.NotImplementedException;
import org.spongepowered.asm.mixin.*;

@Mixin(SurfaceRules.Context.class)
public abstract class MixinSurfaceRules$Context {

    @Shadow
    private static int blockCoordToSurfaceCell(int blockCoord) {
        throw new NotImplementedException();
    }

    @Shadow
    int blockZ;
    @Shadow
    int blockX;
    @Shadow
    @Final
    @Mutable
    private NoiseChunk noiseChunk;

    @Shadow
    private static int surfaceCellToBlockCoord(int surfaceCell) {
        throw new NotImplementedException();
    }

    @Shadow
    private int minSurfaceLevel;
    @Shadow
    int surfaceDepth;

    @Shadow
    long lastUpdateXZ;
    @Shadow
    @Final
    @Mutable
    ChunkAccess chunk;

    @Final
    @Shadow
    @Mutable
    WorldGenerationContext context;

    @Shadow
    private long lastMinSurfaceLevelUpdate;

    @Shadow
    private long lastPreliminarySurfaceCellOrigin;

    @Shadow
    @Final
    private int[] preliminarySurfaceCache;

    /**
     * @author Sixik
     * @reason Faster math for min surface level calculation
     */
    @Overwrite
    public int getMinSurfaceLevel() {
        final long lastUpdateXZ = this.lastUpdateXZ;

        if (this.lastMinSurfaceLevelUpdate != lastUpdateXZ) {
            this.lastMinSurfaceLevelUpdate = lastUpdateXZ;
            final int bX = this.blockX;
            final int bZ = this.blockZ;

            final int cellX = bX >> 4;
            final int cellZ = bZ >> 4;
            final long cellKey = ((long) cellZ << 32) | (cellX & 0xFFFFFFFFL);

            final int[] cache = this.preliminarySurfaceCache;

            if (this.lastPreliminarySurfaceCellOrigin != cellKey) {
                this.lastPreliminarySurfaceCellOrigin = cellKey;

                final int x0 = cellX << 4;
                final int x1 = x0 + 16;
                final int z0 = cellZ << 4;
                final int z1 = z0 + 16;

                final NoiseChunk noiseChunk = this.noiseChunk;
                cache[0] = noiseChunk.preliminarySurfaceLevel(x0, z0);
                cache[1] = noiseChunk.preliminarySurfaceLevel(x1, z0);
                cache[2] = noiseChunk.preliminarySurfaceLevel(x0, z1);
                cache[3] = noiseChunk.preliminarySurfaceLevel(x1, z1);
            }

            final double fx = (bX & 15) * 0.0625D; // 1/16
            final double fz = (bZ & 15) * 0.0625D;

            final double v0 = cache[0];
            final double v1 = cache[1];
            final double v2 = cache[2];
            final double v3 = cache[3];

            final double lerpX1 = v0 + fx * (v1 - v0);
            final double lerpX2 = v2 + fx * (v3 - v2);
            final int interpolatedY = (int) (lerpX1 + fz * (lerpX2 - lerpX1));

            this.minSurfaceLevel = interpolatedY + this.surfaceDepth - 8;
        }
        return this.minSurfaceLevel;
    }
}
