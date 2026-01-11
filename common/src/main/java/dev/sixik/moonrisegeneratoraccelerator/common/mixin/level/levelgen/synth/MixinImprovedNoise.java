package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.synth;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.*;

@Mixin(ImprovedNoise.class)
public abstract class MixinImprovedNoise {

    @Shadow
    @Final
    public double xo;
    @Shadow
    @Final
    public double yo;
    @Shadow
    @Final
    public double zo;

    @Shadow
    protected abstract double sampleWithDerivative(int i, int j, int k, double d, double e, double f, double[] ds);

    @Shadow
    @Final
    private byte[] p;
    
    @Unique
    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
            1, 1, 0, 0,
            -1, 1, 0, 0,
            1, -1, 0, 0,
            -1, -1, 0, 0,
            1, 0, 1, 0,
            -1, 0, 1, 0,
            1, 0, -1, 0,
            -1, 0, -1, 0,
            0, 1, 1, 0,
            0, -1, 1, 0,
            0, 1, -1, 0,
            0, -1, -1, 0,
            1, 1, 0, 0,
            0, -1, 1, 0,
            -1, 1, 0, 0,
            0, -1, -1, 0,
    };

    /**
     * @author Sixik
     * @reason
     */
    @Deprecated
    @Overwrite
    public double noise(double d, double e, double f, double g, double h) {
        final double i = d + this.xo;
        final double j = e + this.yo;
        final double k = f + this.zo;
        final double floor = Math.floor(i);
        final double floor1 = Math.floor(j);
        final double floor2 = Math.floor(k);
        final double o = i - floor;
        final double p = j - floor1;
        final double q = k - floor2;
        final double s;
        if (g != (double)0.0F) {
            double r;
            if (h >= (double)0.0F && h < p) {
                r = h;
            } else {
                r = p;
            }

            s = Math.floor(r / g + 1.0E-7) * g;
        } else {
            s = 0.0F;
        }

        return this.sampleAndLerp((int) floor, (int) floor1, (int) floor2, o, p - s, q, p);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double noiseWithDerivative(double d, double e, double f, double[] ds) {
        double g = d + this.xo;
        double h = e + this.yo;
        double i = f + this.zo;
        double j = Math.floor(g);
        double k = Math.floor(h);
        double l = Math.floor(i);
        double m = g - j;
        double n = h - k;
        double o = i - l;
        return this.sampleWithDerivative((int) j, (int) k, (int) l, m, n, o, ds);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    private double sampleAndLerp(int gridX, int gridY, int gridZ, double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        final int var0 = gridX & 0xFF;
        final int var1 = (gridX + 1) & 0xFF;
        final int var2 = this.p[var0] & 0xFF;
        final int var3 = this.p[var1] & 0xFF;
        final int var4 = (var2 + gridY) & 0xFF;
        final int var5 = (var3 + gridY) & 0xFF;
        final int var6 = (var2 + gridY + 1) & 0xFF;
        final int var7 = (var3 + gridY + 1) & 0xFF;
        final int var8 = this.p[var4] & 0xFF;
        final int var9 = this.p[var5] & 0xFF;
        final int var10 = this.p[var6] & 0xFF;
        final int var11 = this.p[var7] & 0xFF;
        final int var12 = (var8 + gridZ) & 0xFF;
        final int var13 = (var9 + gridZ) & 0xFF;
        final int var14 = (var10 + gridZ) & 0xFF;
        final int var15 = (var11 + gridZ) & 0xFF;
        final int var16 = (var8 + gridZ + 1) & 0xFF;
        final int var17 = (var9 + gridZ + 1) & 0xFF;
        final int var18 = (var10 + gridZ + 1) & 0xFF;
        final int var19 = (var11 + gridZ + 1) & 0xFF;
        final int var20 = (this.p[var12] & 15) << 2;
        final int var21 = (this.p[var13] & 15) << 2;
        final int var22 = (this.p[var14] & 15) << 2;
        final int var23 = (this.p[var15] & 15) << 2;
        final int var24 = (this.p[var16] & 15) << 2;
        final int var25 = (this.p[var17] & 15) << 2;
        final int var26 = (this.p[var18] & 15) << 2;
        final int var27 = (this.p[var19] & 15) << 2;
        final double var60 = deltaX - 1.0;
        final double var61 = weirdDeltaY - 1.0;
        final double var62 = deltaZ - 1.0;
        final double var87 = FLAT_SIMPLEX_GRAD[(var20) | 0] * deltaX + FLAT_SIMPLEX_GRAD[(var20) | 1] * weirdDeltaY + FLAT_SIMPLEX_GRAD[(var20) | 2] * deltaZ;
        final double var88 = FLAT_SIMPLEX_GRAD[(var21) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var21) | 1] * weirdDeltaY + FLAT_SIMPLEX_GRAD[(var21) | 2] * deltaZ;
        final double var89 = FLAT_SIMPLEX_GRAD[(var22) | 0] * deltaX + FLAT_SIMPLEX_GRAD[(var22) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var22) | 2] * deltaZ;
        final double var90 = FLAT_SIMPLEX_GRAD[(var23) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var23) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var23) | 2] * deltaZ;
        final double var91 = FLAT_SIMPLEX_GRAD[(var24) | 0] * deltaX + FLAT_SIMPLEX_GRAD[(var24) | 1] * weirdDeltaY + FLAT_SIMPLEX_GRAD[(var24) | 2] * var62;
        final double var92 = FLAT_SIMPLEX_GRAD[(var25) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var25) | 1] * weirdDeltaY + FLAT_SIMPLEX_GRAD[(var25) | 2] * var62;
        final double var93 = FLAT_SIMPLEX_GRAD[(var26) | 0] * deltaX + FLAT_SIMPLEX_GRAD[(var26) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var26) | 2] * var62;
        final double var94 = FLAT_SIMPLEX_GRAD[(var27) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var27) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var27) | 2] * var62;
        final double var95 = deltaX * 6.0 - 15.0;
        final double var96 = deltaY * 6.0 - 15.0;
        final double var97 = deltaZ * 6.0 - 15.0;
        final double var98 = deltaX * var95 + 10.0;
        final double var99 = deltaY * var96 + 10.0;
        final double var100 = deltaZ * var97 + 10.0;
        final double var101 = deltaX * deltaX * deltaX * var98;
        final double var102 = deltaY * deltaY * deltaY * var99;
        final double var103 = deltaZ * deltaZ * deltaZ * var100;
        final double var113 = var87 + var101 * (var88 - var87);
        final double var114 = var93 + var101 * (var94 - var93);
        final double var115 = var91 + var101 * (var92 - var91);
        final double var116 = var89 + var101 * (var90 - var89);
        final double var117 = var114 - var115;
        final double var118 = var102 * (var116 - var113);
        final double var119 = var102 * var117;
        final double var120 = var113 + var118;
        final double var121 = var115 + var119;
        return var120 + (var103 * (var121 - var120));
    }
}
