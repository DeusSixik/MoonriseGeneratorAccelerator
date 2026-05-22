package dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DfcJavaNoiseKernelTest {
    private static final double EPSILON = 1.0E-12D;

    @Test
    void wrapAxisMatchesGeneratedRuntimeHelper() {
        double[] values = {
                0.0D,
                -0.0D,
                1.0D,
                -1.0D,
                33_554_432.0D,
                -33_554_432.0D,
                33_554_432.5D,
                -33_554_432.5D,
                16_777_216.0D,
                -16_777_216.0D,
                123_456_789.25D,
                -123_456_789.25D,
                9.007199254740992E15D,
                -9.007199254740992E15D
        };

        for (double value : values) {
            assertEquals(Runtime.wrapAxis(value), DfcJavaNoiseKernel.wrapAxis(value), 0.0D);
        }
    }

    @Test
    void sampleImprovedMatchesVanillaImprovedNoise() throws Exception {
        ImprovedNoise noise = improved(1234L);
        Method sampleImproved = DfcJavaNoiseKernel.class.getDeclaredMethod(
                "sampleImproved",
                byte[].class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class
        );
        sampleImproved.setAccessible(true);

        double[][] points = {
                {0.0D, 0.0D, 0.0D, 0.0D, 0.0D},
                {1.25D, -7.5D, 12.75D, 0.0D, 0.0D},
                {-48.125D, 31.875D, -0.5D, 8.0D, 24.0D},
                {DfcJavaNoiseKernel.wrapAxis(123_456_789.25D), -12.25D, 77.5D, 4.0D, -3.0D}
        };

        byte[] p = permutation(noise);
        double xo = origin(noise, "xo");
        double yo = origin(noise, "yo");
        double zo = origin(noise, "zo");
        for (double[] point : points) {
            double x = point[0];
            double y = point[1];
            double z = point[2];
            double yScale = point[3];
            double yMax = point[4];
            double expected = noise.noise(x, y, z, yScale, yMax);
            double actual = (double) sampleImproved.invoke(null, p, xo, yo, zo, x, y, z, yScale, yMax);
            assertEquals(expected, actual, EPSILON);
        }
    }

    @Test
    void sampleNormalMatchesUnrolledNormalNoiseFormula() throws Exception {
        ImprovedNoise[] first = {
                improved(11L),
                improved(12L)
        };
        ImprovedNoise[] second = {
                improved(21L),
                improved(22L),
                improved(23L)
        };
        double[] firstInput = {1.0D, 0.5D};
        double[] firstAmp = {0.75D, 0.25D};
        double[] secondInput = {1.0D, 0.5D, 0.25D};
        double[] secondAmp = {0.5D, 0.25D, 0.125D};
        double secondScale = 1.0181268882175227D;
        DfcJavaNoiseKernel.NormalKernel kernel = normalKernel(
                0.16666666666666666D,
                branch(first, 1.0D, firstInput, firstAmp),
                branch(second, secondScale, secondInput, secondAmp)
        );

        double[][] points = {
                {0.0D, 64.0D, 0.0D},
                {32.25D, -17.5D, 48.75D},
                {-12_345.125D, 128.0D, 9_876.5D},
                {DfcJavaNoiseKernel.wrapAxis(123_456_789.25D), -64.25D, -DfcJavaNoiseKernel.wrapAxis(44_000_000.75D)}
        };
        for (double[] point : points) {
            double expected = (branchExpected(first, firstInput, firstAmp, 1.0D, point)
                    + branchExpected(second, secondInput, secondAmp, secondScale, point))
                    * 0.16666666666666666D;
            double actual = DfcJavaNoiseKernel.sampleNormal(kernel, point[0], point[1], point[2]);
            assertEquals(expected, actual, EPSILON);
        }
    }

    @Test
    void sampleBlendedMatchesUnrolledBlendedNoiseFormula() throws Exception {
        ImprovedNoise[] main = {
                improved(101L),
                improved(102L),
                improved(103L)
        };
        ImprovedNoise[] min = {
                improved(201L),
                improved(202L),
                improved(203L),
                improved(204L)
        };
        ImprovedNoise[] max = {
                improved(301L),
                improved(302L),
                improved(303L),
                improved(304L)
        };
        double[] mainFactors = {1.0D, 0.5D, 0.125D};
        double[] minFactors = {1.0D, 0.5D, 0.25D, 0.0625D};
        double[] maxFactors = {1.0D, 0.5D, 0.25D, 0.0625D};
        double xzMultiplier = 684.412D;
        double yMultiplier = 684.412D;
        double xzFactor = 80.0D;
        double yFactor = 160.0D;
        double smearScaleMultiplier = 8.0D;
        DfcJavaNoiseKernel.BlendedKernel kernel = blendedKernel(
                xzMultiplier,
                yMultiplier,
                xzFactor,
                yFactor,
                smearScaleMultiplier,
                blendedBranch(main, mainFactors),
                blendedBranch(min, minFactors),
                blendedBranch(max, maxFactors)
        );

        int[][] points = {
                {0, 64, 0},
                {24, -32, 48},
                {-300, 128, 700},
                {1_234_567, -64, -765_432}
        };
        for (int[] point : points) {
            double expected = blendedExpected(
                    main,
                    min,
                    max,
                    mainFactors,
                    minFactors,
                    maxFactors,
                    xzMultiplier,
                    yMultiplier,
                    xzFactor,
                    yFactor,
                    smearScaleMultiplier,
                    point[0],
                    point[1],
                    point[2]
            );
            double actual = DfcJavaNoiseKernel.sampleBlended(kernel, point[0], point[1], point[2]);
            assertEquals(expected, actual, EPSILON);
        }
    }

    private static ImprovedNoise improved(long seed) {
        return new ImprovedNoise(new XoroshiroRandomSource(seed, seed ^ 0x5DEECE66DL));
    }

    private static byte[] permutation(ImprovedNoise noise) throws Exception {
        Field field = ImprovedNoise.class.getDeclaredField("p");
        field.setAccessible(true);
        return (byte[]) field.get(noise);
    }

    private static double origin(ImprovedNoise noise, String fieldName) throws Exception {
        Field field = ImprovedNoise.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getDouble(noise);
    }

    private static Object branch(
            ImprovedNoise[] octaves,
            double inputCoordScale,
            double[] inputFactors,
            double[] ampValueFactors
    ) throws Exception {
        Class<?> branchClass = Class.forName(DfcJavaNoiseKernel.class.getName() + "$Branch");
        Constructor<?> constructor = branchClass.getDeclaredConstructor(
                byte[][].class,
                double[].class,
                double[].class,
                double[].class,
                double.class,
                double[].class,
                double[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                permutations(octaves),
                origins(octaves, "xo"),
                origins(octaves, "yo"),
                origins(octaves, "zo"),
                inputCoordScale,
                inputFactors,
                ampValueFactors
        );
    }

    private static DfcJavaNoiseKernel.NormalKernel normalKernel(
            double valueFactor,
            Object first,
            Object second
    ) throws Exception {
        Class<?> branchClass = Class.forName(DfcJavaNoiseKernel.class.getName() + "$Branch");
        Constructor<DfcJavaNoiseKernel.NormalKernel> constructor =
                DfcJavaNoiseKernel.NormalKernel.class.getDeclaredConstructor(double.class, branchClass, branchClass);
        constructor.setAccessible(true);
        return constructor.newInstance(valueFactor, first, second);
    }

    private static Object blendedBranch(ImprovedNoise[] octaves, double[] factors) throws Exception {
        Class<?> branchClass = Class.forName(DfcJavaNoiseKernel.class.getName() + "$BlendedBranch");
        Constructor<?> constructor = branchClass.getDeclaredConstructor(
                byte[][].class,
                double[].class,
                double[].class,
                double[].class,
                double[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                permutations(octaves),
                origins(octaves, "xo"),
                origins(octaves, "yo"),
                origins(octaves, "zo"),
                factors
        );
    }

    private static DfcJavaNoiseKernel.BlendedKernel blendedKernel(
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier,
            Object main,
            Object min,
            Object max
    ) throws Exception {
        Class<?> branchClass = Class.forName(DfcJavaNoiseKernel.class.getName() + "$BlendedBranch");
        Constructor<DfcJavaNoiseKernel.BlendedKernel> constructor =
                DfcJavaNoiseKernel.BlendedKernel.class.getDeclaredConstructor(
                        double.class,
                        double.class,
                        double.class,
                        double.class,
                        double.class,
                        branchClass,
                        branchClass,
                        branchClass
                );
        constructor.setAccessible(true);
        return constructor.newInstance(
                xzMultiplier,
                yMultiplier,
                xzFactor,
                yFactor,
                smearScaleMultiplier,
                main,
                min,
                max
        );
    }

    private static byte[][] permutations(ImprovedNoise[] octaves) throws Exception {
        byte[][] out = new byte[octaves.length][];
        for (int i = 0; i < octaves.length; i++) {
            out[i] = permutation(octaves[i]);
        }
        return out;
    }

    private static double[] origins(ImprovedNoise[] octaves, String fieldName) throws Exception {
        double[] out = new double[octaves.length];
        for (int i = 0; i < octaves.length; i++) {
            out[i] = origin(octaves[i], fieldName);
        }
        return out;
    }

    private static double branchExpected(
            ImprovedNoise[] octaves,
            double[] inputFactors,
            double[] ampValueFactors,
            double inputCoordScale,
            double[] point
    ) {
        double x = point[0] * inputCoordScale;
        double y = point[1] * inputCoordScale;
        double z = point[2] * inputCoordScale;
        double sum = 0.0D;
        for (int i = 0; i < octaves.length; i++) {
            double factor = inputFactors[i];
            sum += octaves[i].noise(
                    DfcJavaNoiseKernel.wrapAxis(x * factor),
                    DfcJavaNoiseKernel.wrapAxis(y * factor),
                    DfcJavaNoiseKernel.wrapAxis(z * factor)
            ) * ampValueFactors[i];
        }
        return sum;
    }

    private static double blendedExpected(
            ImprovedNoise[] mainOctaves,
            ImprovedNoise[] minOctaves,
            ImprovedNoise[] maxOctaves,
            double[] mainFactors,
            double[] minFactors,
            double[] maxFactors,
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier,
            int blockX,
            int blockY,
            int blockZ
    ) {
        double scaledX = (double) blockX * xzMultiplier;
        double scaledY = (double) blockY * yMultiplier;
        double scaledZ = (double) blockZ * xzMultiplier;
        double mainX = scaledX / xzFactor;
        double mainY = scaledY / yFactor;
        double mainZ = scaledZ / xzFactor;
        double limitYScale = yMultiplier * smearScaleMultiplier;
        double mainYScale = limitYScale / yFactor;

        double main = 0.0D;
        for (int i = 0; i < mainOctaves.length; i++) {
            double factor = mainFactors[i];
            main += mainOctaves[i].noise(
                    DfcJavaNoiseKernel.wrapAxis(mainX * factor),
                    DfcJavaNoiseKernel.wrapAxis(mainY * factor),
                    DfcJavaNoiseKernel.wrapAxis(mainZ * factor),
                    mainYScale * factor,
                    mainY * factor
            ) / factor;
        }

        double blend = (main / 10.0D + 1.0D) * 0.5D;
        double min = blend < 1.0D
                ? limitExpected(minOctaves, minFactors, scaledX, scaledY, scaledZ, limitYScale)
                : 0.0D;
        double max = blend > 0.0D
                ? limitExpected(maxOctaves, maxFactors, scaledX, scaledY, scaledZ, limitYScale)
                : 0.0D;
        double clampedBlend = blend < 0.0D ? 0.0D : Math.min(blend, 1.0D);
        return (min / 512.0D + clampedBlend * (max / 512.0D - min / 512.0D)) / 128.0D;
    }

    private static double limitExpected(
            ImprovedNoise[] octaves,
            double[] factors,
            double x,
            double y,
            double z,
            double yScale
    ) {
        double sum = 0.0D;
        for (int i = 0; i < octaves.length; i++) {
            double factor = factors[i];
            sum += octaves[i].noise(
                    DfcJavaNoiseKernel.wrapAxis(x * factor),
                    DfcJavaNoiseKernel.wrapAxis(y * factor),
                    DfcJavaNoiseKernel.wrapAxis(z * factor),
                    yScale * factor,
                    y * factor
            ) / factor;
        }
        return sum;
    }
}
