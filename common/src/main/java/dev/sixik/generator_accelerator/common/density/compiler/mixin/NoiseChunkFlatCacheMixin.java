package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$FlatCache$FlatArray;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mirrors {@code NoiseChunk.FlatCache#compute} in-buffer path
 * (2D layout over quart indices).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache")
public abstract class NoiseChunkFlatCacheMixin implements DfcCellCacheAccess {

    @Shadow
    @Final
    private NoiseChunk field_36611;

    @Shadow
    @Final
    private double[][] values;

    @Unique
    private NoiseChunk$FlatCache$FlatArray dfc$flatAccess;

    @Unique
    private boolean dfc$flatAccessResolved;

    @Unique
    private int dfc$side;

    @Unique
    private int dfc$area;

    @Override
    public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
        if (context instanceof NoiseChunk chunk) {
            return dfc$tryDirectRead(chunk);
        }

        final int side = dfc$side();
        final int area = dfc$area();

        final int k = (context.blockX() >> 2) - field_36611.firstNoiseX;
        final int l = (context.blockZ() >> 2) - field_36611.firstNoiseZ;

        if (k >= 0 && l >= 0 && k < side && l < side) {
            final double[] flat = dfc$flatArray();
            if (flat != null && flat.length >= area) {
                return flat[k * side + l];
            }

            final double[][] vanillaValues = this.values;
            if (vanillaValues != null && k < vanillaValues.length) {
                final double[] row = vanillaValues[k];
                if (row != null && l < row.length) {
                    return row[l];
                }
            }
        }

        return DfcCacheFastPath.CACHE_MISS;
    }

    @Override
    public double dfc$tryDirectRead(NoiseChunk chunk) {
        final int side = dfc$side();
        final int area = dfc$area();

        final int k = ((chunk.cellStartBlockX + chunk.inCellX) >> 2) - field_36611.firstNoiseX;
        final int l = ((chunk.cellStartBlockZ + chunk.inCellZ) >> 2) - field_36611.firstNoiseZ;

        if (k >= 0 && l >= 0 && k < side && l < side) {
            final double[] flat = dfc$flatArray();
            if (flat != null && flat.length >= area) {
                return flat[k * side + l];
            }

            final double[][] vanillaValues = this.values;
            if (vanillaValues != null && k < vanillaValues.length) {
                final double[] row = vanillaValues[k];
                if (row != null && l < row.length) {
                    return row[l];
                }
            }
        }

        return DfcCacheFastPath.CACHE_MISS;
    }

    @Unique
    private int dfc$side() {
        int side = this.dfc$side;
        if (side == 0) {
            side = this.field_36611.noiseSizeXZ + 1;
            this.dfc$side = side;
            this.dfc$area = side * side;
        }
        return side;
    }

    @Unique
    private int dfc$area() {
        int area = this.dfc$area;
        if (area == 0) {
            dfc$side();
            area = this.dfc$area;
        }
        return area;
    }

    @Unique
    private double[] dfc$flatArray() {
        NoiseChunk$FlatCache$FlatArray flatAccess = this.dfc$flatAccess;
        if (flatAccess == null && !this.dfc$flatAccessResolved) {
            this.dfc$flatAccessResolved = true;
            if (this instanceof NoiseChunk$FlatCache$FlatArray access) {
                flatAccess = access;
                this.dfc$flatAccess = access;
            }
        }
        return flatAccess == null ? null : flatAccess.bts$getArray();
    }
}
