package dev.sixik.generator_accelerator.common.noise.mixin.synth;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.*;

@Mixin(ImprovedNoise.class)
public abstract class MixinImprovedNoise$optimization_through_flat_math {

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
    @Final
    public byte[] p;

    @Unique
    private static final double[] GA_FLAT_SIMPLEX_GRAD = {
            1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0,
            1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0, 0.0,
            1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0,
            1.0, 0.0, -1.0, 0.0, -1.0, 0.0, -1.0, 0.0,
            0.0, 1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            0.0, 1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0,
            1.0, 1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0, 0.0, 0.0, -1.0, -1.0, 0.0
    };

    /**
     * @author Sixik
     * @reason Extreme optimization: fast floor, local hoisting, and parity-safe Y scaling.
     */
    @Deprecated
    @Overwrite
    public double noise(double x, double y, double z, double yScale, double yMax) {
        final double inputX = x + this.xo;
        final double inputY = y + this.yo;
        final double inputZ = z + this.zo;

        final int gridX = (int) Math.floor(inputX);
        final int gridY = (int) Math.floor(inputY);
        final int gridZ = (int) Math.floor(inputZ);

        final double deltaX = inputX - gridX;
        final double deltaY = inputY - gridY;
        final double deltaZ = inputZ - gridZ;

        final double weirdDeltaY;
        if (yScale != 0.0) {
            final double range = (yMax >= 0.0 && yMax < deltaY) ? yMax : deltaY;
            final double scaled = range / yScale + 1.0E-7;

            int scaledFloor = (int) scaled;
            if (scaled < scaledFloor) scaledFloor--;

            weirdDeltaY = deltaY - scaledFloor * yScale;
        } else {
            weirdDeltaY = deltaY;
        }

        return this.sampleAndLerp(gridX, gridY, gridZ, deltaX, weirdDeltaY, deltaZ, deltaY);
    }

    /**
     * @author Sixik
     * @reason Fast floor and local hoisting for derivative sampling.
     */
    @Overwrite
    public double noiseWithDerivative(double x, double y, double z, double[] values) {
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

        return this.sampleWithDerivative(gridX, gridY, gridZ, deltaX, deltaY, deltaZ, values);
    }

    /**
     * @author Sixik
     * @reason Local variable hoisting for array 'p' and gradient lookups.
     */
    @Overwrite
    private double sampleAndLerp(int gridX, int gridY, int gridZ, double x, double wy, double z, double y) {
        final byte[] p = this.p;
        final double[] grad = GA_FLAT_SIMPLEX_GRAD;

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

        final double gi000x = grad[gi000];
        final double gi000y = grad[gi000 | 1];
        final double gi000z = grad[gi000 | 2];
        final double gi001x = grad[gi001];
        final double gi001y = grad[gi001 | 1];
        final double gi001z = grad[gi001 | 2];
        final double gi010x = grad[gi010];
        final double gi010y = grad[gi010 | 1];
        final double gi010z = grad[gi010 | 2];
        final double gi011x = grad[gi011];
        final double gi011y = grad[gi011 | 1];
        final double gi011z = grad[gi011 | 2];
        final double gi100x = grad[gi100];
        final double gi100y = grad[gi100 | 1];
        final double gi100z = grad[gi100 | 2];
        final double gi101x = grad[gi101];
        final double gi101y = grad[gi101 | 1];
        final double gi101z = grad[gi101 | 2];
        final double gi110x = grad[gi110];
        final double gi110y = grad[gi110 | 1];
        final double gi110z = grad[gi110 | 2];
        final double gi111x = grad[gi111];
        final double gi111y = grad[gi111 | 1];
        final double gi111z = grad[gi111 | 2];

        final double n000 = gi000x * x + gi000y * wy + gi000z * z;
        final double n100 = gi100x * x1 + gi100y * wy + gi100z * z;
        final double n001 = gi001x * x + gi001y * wy + gi001z * z1;
        final double n101 = gi101x * x1 + gi101y * wy + gi101z * z1;
        final double n011 = gi011x * x + gi011y * wy1 + gi011z * z1;
        final double n111 = gi111x * x1 + gi111y * wy1 + gi111z * z1;
        final double n010_ = gi010x * x + gi010y * wy1 + gi010z * z;
        final double n110_ = gi110x * x1 + gi110y * wy1 + gi110z * z;

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

    /**
     * @author Sixik
     * @reason Local hoisting and flattened gradient access for derivative sampling.
     */
    @Overwrite
    private double sampleWithDerivative(int gridX, int gridY, int gridZ, double x, double y, double z, double[] noiseValues) {
        final byte[] p = this.p;
        final double[] grad = GA_FLAT_SIMPLEX_GRAD;

        final int X = gridX & 0xFF;
        final int Y = gridY & 0xFF;
        final int Z = gridZ & 0xFF;

        final int px0 = p[X] & 0xFF;
        final int px1 = p[(X + 1) & 0xFF] & 0xFF;
        final int a = (px0 + Y) & 0xFF;
        final int b = (px1 + Y) & 0xFF;
        final int aa = (p[a] & 0xFF) + Z;
        final int ab = (p[(a + 1) & 0xFF] & 0xFF) + Z;
        final int ba = (p[b] & 0xFF) + Z;
        final int bb = (p[(b + 1) & 0xFF] & 0xFF) + Z;

        final int gi000 = (p[aa & 0xFF] & 15) << 2;
        final int gi001 = (p[(aa + 1) & 0xFF] & 15) << 2;
        final int gi010 = (p[ab & 0xFF] & 15) << 2;
        final int gi011 = (p[(ab + 1) & 0xFF] & 15) << 2;
        final int gi100 = (p[ba & 0xFF] & 15) << 2;
        final int gi101 = (p[(ba + 1) & 0xFF] & 15) << 2;
        final int gi110 = (p[bb & 0xFF] & 15) << 2;
        final int gi111 = (p[(bb + 1) & 0xFF] & 15) << 2;

        final double x1 = x - 1.0;
        final double y1 = y - 1.0;
        final double z1 = z - 1.0;

        final double gi000x = grad[gi000];
        final double gi000y = grad[gi000 | 1];
        final double gi000z = grad[gi000 | 2];
        final double gi001x = grad[gi001];
        final double gi001y = grad[gi001 | 1];
        final double gi001z = grad[gi001 | 2];
        final double gi010x = grad[gi010];
        final double gi010y = grad[gi010 | 1];
        final double gi010z = grad[gi010 | 2];
        final double gi011x = grad[gi011];
        final double gi011y = grad[gi011 | 1];
        final double gi011z = grad[gi011 | 2];
        final double gi100x = grad[gi100];
        final double gi100y = grad[gi100 | 1];
        final double gi100z = grad[gi100 | 2];
        final double gi101x = grad[gi101];
        final double gi101y = grad[gi101 | 1];
        final double gi101z = grad[gi101 | 2];
        final double gi110x = grad[gi110];
        final double gi110y = grad[gi110 | 1];
        final double gi110z = grad[gi110 | 2];
        final double gi111x = grad[gi111];
        final double gi111y = grad[gi111 | 1];
        final double gi111z = grad[gi111 | 2];

        final double n000 = gi000x * x + gi000y * y + gi000z * z;
        final double n100 = gi100x * x1 + gi100y * y + gi100z * z;
        final double n010 = gi010x * x + gi010y * y1 + gi010z * z;
        final double n110 = gi110x * x1 + gi110y * y1 + gi110z * z;
        final double n001 = gi001x * x + gi001y * y + gi001z * z1;
        final double n101 = gi101x * x1 + gi101y * y + gi101z * z1;
        final double n011 = gi011x * x + gi011y * y1 + gi011z * z1;
        final double n111 = gi111x * x1 + gi111y * y1 + gi111z * z1;

        final double uFactor = x * 6.0 - 15.0;
        final double vFactor = y * 6.0 - 15.0;
        final double wFactor = z * 6.0 - 15.0;
        final double u = x * x * x * (x * uFactor + 10.0);
        final double v = y * y * y * (y * vFactor + 10.0);
        final double w = z * z * z * (z * wFactor + 10.0);

        final double nx00 = n000 + u * (n100 - n000);
        final double nx10 = n010 + u * (n110 - n010);
        final double nx01 = n001 + u * (n101 - n001);
        final double nx11 = n011 + u * (n111 - n011);
        final double nxy0 = nx00 + v * (nx10 - nx00);
        final double nxy1 = nx01 + v * (nx11 - nx01);

        final double lerpGradX00 = gi000x + u * (gi100x - gi000x);
        final double lerpGradX10 = gi010x + u * (gi110x - gi010x);
        final double lerpGradX01 = gi001x + u * (gi101x - gi001x);
        final double lerpGradX11 = gi011x + u * (gi111x - gi011x);
        final double gradX0 = lerpGradX00 + v * (lerpGradX10 - lerpGradX00);
        final double gradX1 = lerpGradX01 + v * (lerpGradX11 - lerpGradX01);
        final double gradX = gradX0 + w * (gradX1 - gradX0);

        final double lerpGradY00 = gi000y + u * (gi100y - gi000y);
        final double lerpGradY10 = gi010y + u * (gi110y - gi010y);
        final double lerpGradY01 = gi001y + u * (gi101y - gi001y);
        final double lerpGradY11 = gi011y + u * (gi111y - gi011y);
        final double gradY0 = lerpGradY00 + v * (lerpGradY10 - lerpGradY00);
        final double gradY1 = lerpGradY01 + v * (lerpGradY11 - lerpGradY01);
        final double gradY = gradY0 + w * (gradY1 - gradY0);

        final double lerpGradZ00 = gi000z + u * (gi100z - gi000z);
        final double lerpGradZ10 = gi010z + u * (gi110z - gi010z);
        final double lerpGradZ01 = gi001z + u * (gi101z - gi001z);
        final double lerpGradZ11 = gi011z + u * (gi111z - gi011z);
        final double gradZ0 = lerpGradZ00 + v * (lerpGradZ10 - lerpGradZ00);
        final double gradZ1 = lerpGradZ01 + v * (lerpGradZ11 - lerpGradZ01);
        final double gradZ = gradZ0 + w * (gradZ1 - gradZ0);

        final double diffX00 = n100 - n000;
        final double diffX10 = n110 - n010;
        final double diffX01 = n101 - n001;
        final double diffX11 = n111 - n011;
        final double lerpDiffX0 = diffX00 + v * (diffX10 - diffX00);
        final double lerpDiffX1 = diffX01 + v * (diffX11 - diffX01);
        final double d14 = lerpDiffX0 + w * (lerpDiffX1 - lerpDiffX0);

        final double diffY00 = n010 - n000;
        final double diffY01 = n011 - n001;
        final double diffY10 = n110 - n100;
        final double diffY11 = n111 - n101;
        final double lerpDiffY0 = diffY00 + w * (diffY01 - diffY00);
        final double lerpDiffY1 = diffY10 + w * (diffY11 - diffY10);
        final double d15 = lerpDiffY0 + u * (lerpDiffY1 - lerpDiffY0);

        final double diffZ00 = n001 - n000;
        final double diffZ10 = n101 - n100;
        final double diffZ01 = n011 - n010;
        final double diffZ11 = n111 - n110;
        final double lerpDiffZ0 = diffZ00 + u * (diffZ10 - diffZ00);
        final double lerpDiffZ1 = diffZ01 + u * (diffZ11 - diffZ01);
        final double d16 = lerpDiffZ0 + v * (lerpDiffZ1 - lerpDiffZ0);

        final double du = 30.0 * x * x * (x * (x - 2.0) + 1.0);
        final double dv = 30.0 * y * y * (y * (y - 2.0) + 1.0);
        final double dw = 30.0 * z * z * (z * (z - 2.0) + 1.0);

        noiseValues[0] += gradX + du * d14;
        noiseValues[1] += gradY + dv * d15;
        noiseValues[2] += gradZ + dw * d16;

        return nxy0 + w * (nxy1 - nxy0);
    }
}
