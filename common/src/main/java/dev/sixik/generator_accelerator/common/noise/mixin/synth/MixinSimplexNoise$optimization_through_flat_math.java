package dev.sixik.generator_accelerator.common.noise.mixin.synth;

import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SimplexNoise.class)
public abstract class MixinSimplexNoise$optimization_through_flat_math {

    @Shadow
    @Final
    private int[] p;

    @Unique
    private static final double GA_SIMPLEX_2D_SKEW = 0.3660254037844386;
    @Unique
    private static final double GA_SIMPLEX_2D_UNSKEW = 0.21132486540518713;
    @Unique
    private static final double GA_SIMPLEX_3D_SKEW = 0.3333333333333333;
    @Unique
    private static final double GA_SIMPLEX_3D_UNSKEW = 0.16666666666666666;

    @Unique
    private static final double[] GA_SIMPLEX_GRAD = {
            1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0,
            1.0, -1.0, 0.0,
            -1.0, -1.0, 0.0,
            1.0, 0.0, 1.0,
            -1.0, 0.0, 1.0,
            1.0, 0.0, -1.0,
            -1.0, 0.0, -1.0,
            0.0, 1.0, 1.0,
            0.0, -1.0, 1.0,
            0.0, 1.0, -1.0,
            0.0, -1.0, -1.0,
            1.0, 1.0, 0.0,
            0.0, -1.0, 1.0,
            -1.0, 1.0, 0.0,
            0.0, -1.0, -1.0
    };

    @Unique
    private static double ga$cornerNoise3D(final int gradientIndex, final double x,
                                           final double y, final double z, final double offset) {
        double atten = offset - x * x - y * y - z * z;
        if (atten < 0.0) {
            return 0.0;
        }

        atten *= atten;
        final int gradIndex3 = gradientIndex * 3;
        return atten * atten * (
                GA_SIMPLEX_GRAD[gradIndex3] * x +
                GA_SIMPLEX_GRAD[gradIndex3 + 1] * y +
                GA_SIMPLEX_GRAD[gradIndex3 + 2] * z
        );
    }

    /**
     * @author Sixik
     * @reason Flat gradient table and local permutation hoisting for 2D simplex noise.
     */
    @Overwrite
    public double getValue(final double x, final double y) {
        final double skew = (x + y) * GA_SIMPLEX_2D_SKEW;

        int cellX = (int) (x + skew);
        if (x + skew < cellX) cellX--;

        int cellY = (int) (y + skew);
        if (y + skew < cellY) cellY--;

        final double unskew = (cellX + cellY) * GA_SIMPLEX_2D_UNSKEW;
        final double x0 = x - (cellX - unskew);
        final double y0 = y - (cellY - unskew);

        final int offsetX;
        final int offsetY;
        if (x0 > y0) {
            offsetX = 1;
            offsetY = 0;
        } else {
            offsetX = 0;
            offsetY = 1;
        }

        final double x1 = x0 - offsetX + GA_SIMPLEX_2D_UNSKEW;
        final double y1 = y0 - offsetY + GA_SIMPLEX_2D_UNSKEW;
        final double x2 = x0 - 1.0 + 2.0 * GA_SIMPLEX_2D_UNSKEW;
        final double y2 = y0 - 1.0 + 2.0 * GA_SIMPLEX_2D_UNSKEW;

        final int[] perm = this.p;
        final int ix = cellX & 0xFF;
        final int iy = cellY & 0xFF;
        final int grad0 = perm[(ix + perm[iy]) & 0xFF] % 12;
        final int grad1 = perm[(ix + offsetX + perm[(iy + offsetY) & 0xFF]) & 0xFF] % 12;
        final int grad2 = perm[(ix + 1 + perm[(iy + 1) & 0xFF]) & 0xFF] % 12;

        final double noise0 = ga$cornerNoise3D(grad0, x0, y0, 0.0, 0.5);
        final double noise1 = ga$cornerNoise3D(grad1, x1, y1, 0.0, 0.5);
        final double noise2 = ga$cornerNoise3D(grad2, x2, y2, 0.0, 0.5);
        return 70.0 * (noise0 + noise1 + noise2);
    }

    /**
     * @author Sixik
     * @reason Flat gradient table and local permutation hoisting for 3D simplex noise.
     */
    @Overwrite
    public double getValue(final double x, final double y, final double z) {
        final double skew = (x + y + z) * GA_SIMPLEX_3D_SKEW;

        int cellX = (int) (x + skew);
        if (x + skew < cellX) cellX--;

        int cellY = (int) (y + skew);
        if (y + skew < cellY) cellY--;

        int cellZ = (int) (z + skew);
        if (z + skew < cellZ) cellZ--;

        final double unskew = (cellX + cellY + cellZ) * GA_SIMPLEX_3D_UNSKEW;
        final double x0 = x - (cellX - unskew);
        final double y0 = y - (cellY - unskew);
        final double z0 = z - (cellZ - unskew);

        final int i1;
        final int j1;
        final int k1;
        final int i2;
        final int j2;
        final int k2;

        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1;
                i2 = 1; j2 = 0; k2 = 1;
            }
        } else if (y0 < z0) {
            i1 = 0; j1 = 0; k1 = 1;
            i2 = 0; j2 = 1; k2 = 1;
        } else if (x0 < z0) {
            i1 = 0; j1 = 1; k1 = 0;
            i2 = 0; j2 = 1; k2 = 1;
        } else {
            i1 = 0; j1 = 1; k1 = 0;
            i2 = 1; j2 = 1; k2 = 0;
        }

        final double x1 = x0 - i1 + GA_SIMPLEX_3D_UNSKEW;
        final double y1 = y0 - j1 + GA_SIMPLEX_3D_UNSKEW;
        final double z1 = z0 - k1 + GA_SIMPLEX_3D_UNSKEW;
        final double x2 = x0 - i2 + 2.0 * GA_SIMPLEX_3D_UNSKEW;
        final double y2 = y0 - j2 + 2.0 * GA_SIMPLEX_3D_UNSKEW;
        final double z2 = z0 - k2 + 2.0 * GA_SIMPLEX_3D_UNSKEW;
        final double x3 = x0 - 1.0 + 3.0 * GA_SIMPLEX_3D_UNSKEW;
        final double y3 = y0 - 1.0 + 3.0 * GA_SIMPLEX_3D_UNSKEW;
        final double z3 = z0 - 1.0 + 3.0 * GA_SIMPLEX_3D_UNSKEW;

        final int[] perm = this.p;
        final int ix = cellX & 0xFF;
        final int iy = cellY & 0xFF;
        final int iz = cellZ & 0xFF;

        final int grad0 = perm[(ix + perm[(iy + perm[iz & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad1 = perm[(ix + i1 + perm[(iy + j1 + perm[(iz + k1) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad2 = perm[(ix + i2 + perm[(iy + j2 + perm[(iz + k2) & 0xFF]) & 0xFF]) & 0xFF] % 12;
        final int grad3 = perm[(ix + 1 + perm[(iy + 1 + perm[(iz + 1) & 0xFF]) & 0xFF]) & 0xFF] % 12;

        final double noise0 = ga$cornerNoise3D(grad0, x0, y0, z0, 0.6);
        final double noise1 = ga$cornerNoise3D(grad1, x1, y1, z1, 0.6);
        final double noise2 = ga$cornerNoise3D(grad2, x2, y2, z2, 0.6);
        final double noise3 = ga$cornerNoise3D(grad3, x3, y3, z3, 0.6);
        return 32.0 * (noise0 + noise1 + noise2 + noise3);
    }
}
