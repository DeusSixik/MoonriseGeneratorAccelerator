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
            1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0, 0,
            1, 0, 1, 0, -1, 0, 1, 0, 1, 0, -1, 0, -1, 0, -1, 0,
            0, 1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0,
            1, 1, 0, 0, 0, -1, 1, 0, -1, 1, 0, 0, 0, -1, -1, 0,
    };

    /**
     * @author Sixik
     * @reason Extreme optimization: Fast floor, Stack hoisting, Unrolled math.
     */
    @Deprecated
    @Overwrite
    public double noise(double x, double y, double z, double yScale, double yMax) {
        final double inputX = x + this.xo;
        final double inputY = y + this.yo;
        final double inputZ = z + this.zo;

        int gridX = (int) inputX;
        if (inputX < gridX) gridX--;

        int gridY = (int) inputY;
        if (inputY < gridY) gridY--;

        int gridZ = (int) inputZ;
        if (inputZ < gridZ) gridZ--;

        final double deltaX = inputX - gridX;
        final double deltaY = inputY - gridY;
        final double deltaZ = inputZ - gridZ;

        final double weirdDeltaY;
        if (yScale != 0.0) {
            final double range;
            if (yMax >= 0.0 && yMax < deltaY) {
                range = yMax;
            } else {
                range = deltaY;
            }

            final double scaled = range / yScale + 1.0E-7;
            int scaledFloor = (int) scaled;
            if (scaled < scaledFloor) scaledFloor--;

            weirdDeltaY = deltaY - (scaledFloor * yScale);
        } else {
            weirdDeltaY = deltaY;
        }

        return this.bts$sampleAndLerp(gridX, gridY, gridZ, deltaX, weirdDeltaY, deltaZ, deltaY);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public double noiseWithDerivative(double d, double e, double f, double[] ds) {
        final double g = d + this.xo;
        final double h = e + this.yo;
        final double i = f + this.zo;
        final double j = Math.floor(g);
        final double k = Math.floor(h);
        final double l = Math.floor(i);
        final double m = g - j;
        final double n = h - k;
        final double o = i - l;
        return this.sampleWithDerivative((int) j, (int) k, (int) l, m, n, o, ds);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    private double sampleAndLerp(int gridX, int gridY, int gridZ, double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        /*
            JIT will require fewer instructions to read data than if you access a field in a class
         */
        final double[] local_FLAT_SIMPLEX_GRAD = FLAT_SIMPLEX_GRAD;

        final byte[] p = this.p;

        final int var0 = gridX & 0xFF;
        final int var1 = (gridX + 1) & 0xFF;
        final int var2 = p[var0] & 0xFF;
        final int var3 = p[var1] & 0xFF;
        final int var4 = (var2 + gridY) & 0xFF;
        final int var5 = (var3 + gridY) & 0xFF;
        final int var6 = (var2 + gridY + 1) & 0xFF;
        final int var7 = (var3 + gridY + 1) & 0xFF;
        final int var8 = p[var4] & 0xFF;
        final int var9 = p[var5] & 0xFF;
        final int var10 = p[var6] & 0xFF;
        final int var11 = p[var7] & 0xFF;
        final int var12 = (var8 + gridZ) & 0xFF;
        final int var13 = (var9 + gridZ) & 0xFF;
        final int var14 = (var10 + gridZ) & 0xFF;
        final int var15 = (var11 + gridZ) & 0xFF;
        final int var16 = (var8 + gridZ + 1) & 0xFF;
        final int var17 = (var9 + gridZ + 1) & 0xFF;
        final int var18 = (var10 + gridZ + 1) & 0xFF;
        final int var19 = (var11 + gridZ + 1) & 0xFF;
        final int var20 = (p[var12] & 15) << 2;
        final int var21 = (p[var13] & 15) << 2;
        final int var22 = (p[var14] & 15) << 2;
        final int var23 = (p[var15] & 15) << 2;
        final int var24 = (p[var16] & 15) << 2;
        final int var25 = (p[var17] & 15) << 2;
        final int var26 = (p[var18] & 15) << 2;
        final int var27 = (p[var19] & 15) << 2;
        final double var60 = deltaX - 1.0;
        final double var61 = weirdDeltaY - 1.0;
        final double var62 = deltaZ - 1.0;
        final double var87 = local_FLAT_SIMPLEX_GRAD[(var20)] * deltaX + local_FLAT_SIMPLEX_GRAD[(var20) | 1] * weirdDeltaY + local_FLAT_SIMPLEX_GRAD[(var20) | 2] * deltaZ;
        final double var88 = local_FLAT_SIMPLEX_GRAD[(var21)] * var60 + local_FLAT_SIMPLEX_GRAD[(var21) | 1] * weirdDeltaY + local_FLAT_SIMPLEX_GRAD[(var21) | 2] * deltaZ;
        final double var89 = local_FLAT_SIMPLEX_GRAD[(var22)] * deltaX + local_FLAT_SIMPLEX_GRAD[(var22) | 1] * var61 + local_FLAT_SIMPLEX_GRAD[(var22) | 2] * deltaZ;
        final double var90 = local_FLAT_SIMPLEX_GRAD[(var23)] * var60 + local_FLAT_SIMPLEX_GRAD[(var23) | 1] * var61 + local_FLAT_SIMPLEX_GRAD[(var23) | 2] * deltaZ;
        final double var91 = local_FLAT_SIMPLEX_GRAD[(var24)] * deltaX + local_FLAT_SIMPLEX_GRAD[(var24) | 1] * weirdDeltaY + local_FLAT_SIMPLEX_GRAD[(var24) | 2] * var62;
        final double var92 = local_FLAT_SIMPLEX_GRAD[(var25)] * var60 + local_FLAT_SIMPLEX_GRAD[(var25) | 1] * weirdDeltaY + local_FLAT_SIMPLEX_GRAD[(var25) | 2] * var62;
        final double var93 = local_FLAT_SIMPLEX_GRAD[(var26)] * deltaX + local_FLAT_SIMPLEX_GRAD[(var26) | 1] * var61 + local_FLAT_SIMPLEX_GRAD[(var26) | 2] * var62;
        final double var94 = local_FLAT_SIMPLEX_GRAD[(var27)] * var60 + local_FLAT_SIMPLEX_GRAD[(var27) | 1] * var61 + local_FLAT_SIMPLEX_GRAD[(var27) | 2] * var62;
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

    /**
     * @author Sixik
     * @reason Local variable hoisting for array 'p' + SIMD-friendly math structure.
     */
    @Unique
    private double bts$sampleAndLerp(int gridX, int gridY, int gridZ, double x, double wy, double z, double y) {
        /*
            JIT will require fewer instructions to read data than if you access a field in a class
         */
        final double[] local_FLAT_SIMPLEX_GRAD = FLAT_SIMPLEX_GRAD;
        final byte[] p = this.p;

        final int X = gridX & 0xFF;
        final int Y = gridY & 0xFF;
        final int Z = gridZ & 0xFF;

        // A = p[X] + Y
        final int A = (p[X] & 0xFF) + Y;
        final int AA = (p[A & 0xFF] & 0xFF) + Z;
        final int AB = (p[(A + 1) & 0xFF] & 0xFF) + Z;

        // B = p[X + 1] + Y
        final int B = (p[(X + 1) & 0xFF] & 0xFF) + Y;
        final int BA = (p[B & 0xFF] & 0xFF) + Z;
        final int BB = (p[(B + 1) & 0xFF] & 0xFF) + Z;

        final int gi000 = (p[AA & 0xFF] & 15) << 2;
        final int gi001 = (p[(AA + 1) & 0xFF] & 15) << 2;
        final int gi010 = (p[AB & 0xFF] & 15) << 2;
        final int gi011 = (p[(AB + 1) & 0xFF] & 15) << 2;
        final int gi100 = (p[BA & 0xFF] & 15) << 2;
        final int gi101 = (p[(BA + 1) & 0xFF] & 15) << 2;
        final int gi110 = (p[BB & 0xFF] & 15) << 2;
        final int gi111 = (p[(BB + 1) & 0xFF] & 15) << 2;

        final double x1 = x - 1.0;
        final double wy1 = wy - 1.0;
        final double z1 = z - 1.0;

        // N000
        final double n000 = local_FLAT_SIMPLEX_GRAD[gi000] * x +
                local_FLAT_SIMPLEX_GRAD[gi000 | 1] * wy +
                local_FLAT_SIMPLEX_GRAD[gi000 | 2] * z;
        // N100
        final double n100 = local_FLAT_SIMPLEX_GRAD[gi100] * x1 +
                local_FLAT_SIMPLEX_GRAD[gi100 | 1] * wy +
                local_FLAT_SIMPLEX_GRAD[gi100 | 2] * z;
        // N010
//        final double n010 = local_FLAT_SIMPLEX_GRAD[gi010] * x +
//                local_FLAT_SIMPLEX_GRAD[gi010 | 1] * (wy - 1.0) +
//                local_FLAT_SIMPLEX_GRAD[gi010 | 2] * z;

        final double n001 = local_FLAT_SIMPLEX_GRAD[gi001] * x + local_FLAT_SIMPLEX_GRAD[gi001 | 1] * wy + local_FLAT_SIMPLEX_GRAD[gi001 | 2] * z1;
        final double n101 = local_FLAT_SIMPLEX_GRAD[gi101] * x1 + local_FLAT_SIMPLEX_GRAD[gi101 | 1] * wy + local_FLAT_SIMPLEX_GRAD[gi101 | 2] * z1;

        final double n011 = local_FLAT_SIMPLEX_GRAD[gi011] * x + local_FLAT_SIMPLEX_GRAD[gi011 | 1] * wy1 + local_FLAT_SIMPLEX_GRAD[gi011 | 2] * z1;
        final double n111 = local_FLAT_SIMPLEX_GRAD[gi111] * x1 + local_FLAT_SIMPLEX_GRAD[gi111 | 1] * wy1 + local_FLAT_SIMPLEX_GRAD[gi111 | 2] * z1;

        final double n010_ = local_FLAT_SIMPLEX_GRAD[gi010] * x + local_FLAT_SIMPLEX_GRAD[gi010 | 1] * wy1 + local_FLAT_SIMPLEX_GRAD[gi010 | 2] * z;
        final double n110_ = local_FLAT_SIMPLEX_GRAD[gi110] * x1 + local_FLAT_SIMPLEX_GRAD[gi110 | 1] * wy1 + local_FLAT_SIMPLEX_GRAD[gi110 | 2] * z;

        final double u = x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
        final double v = y * y * y * (y * (y * 6.0 - 15.0) + 10.0);
        final double w = z * z * z * (z * (z * 6.0 - 15.0) + 10.0);

        final double lerpX1 = n000 + u * (n100 - n000);
        final double lerpX2 = n010_ + u * (n110_ - n010_);
        final double lerpX3 = n001 + u * (n101 - n001);
        final double lerpX4 = n011 + u * (n111 - n011);

        final double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
        final double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);

        return lerpY1 + w * (lerpY2 - lerpY1);
    }
}
