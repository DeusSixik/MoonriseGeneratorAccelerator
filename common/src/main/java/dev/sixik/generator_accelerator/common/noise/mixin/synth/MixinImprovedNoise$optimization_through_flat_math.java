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
            1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0,
            1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0, 0.0
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
            final double range = (yMax >= 0.0 && yMax < deltaY) ? yMax : deltaY;
            final double scaled = range / yScale + 1.0E-7;

            int scaledFloor = (int) scaled;
            if (scaled < scaledFloor) scaledFloor--;

            weirdDeltaY = deltaY - scaledFloor * yScale;
        } else {
            weirdDeltaY = deltaY;
        }

        return this.bts$sampleAndLerp(gridX, gridY, gridZ, deltaX, weirdDeltaY, deltaZ, deltaY);
    }

    /**
     * @author Sixik
     * @reason Local variable hoisting for array 'p' and gradient lookups.
     */
    @Unique
    private double bts$sampleAndLerp(int gridX, int gridY, int gridZ, double x, double wy, double z, double y) {
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
}
