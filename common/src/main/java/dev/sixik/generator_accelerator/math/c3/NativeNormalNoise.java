package dev.sixik.generator_accelerator.math.c3;

public class NativeNormalNoise {

    public static native long create(long random_source_ptr, int firstOctave, double[] amplitudes);

    public static native double getValue(long normal_noise_ptr, double x, double y, double z);

    public static native double getValue2D(long normal_noise_ptr, double x, double z);

    public static native void deleteNoise(long bts$ptr);

    public static long getFirst(long normalNoise_ptr) {
        return getPerlin(normalNoise_ptr, 0);
    }

    public static long getSecond(long normalNoise_ptr) {
        return getPerlin(normalNoise_ptr, 1);
    }

    private static native long getPerlin(long normalNoise_ptr, int type);

    public static native double getMax(long normalNoise_ptr);

    public static native void printParams(long normalNoise_ptr);
}
