package dev.sixik.generator_accelerator.math.c3;

public class NativeRandom {

    public static long create() {
        return create(0, 0, true);
    }

    public static long create(long seed) {
        return create(seed, 0, false);
    }

    public static long createXoroshiro(long seed) {
        return create(seed, 1, false);
    }

    public static native long createXoroshiro(long seedLo, long seedHi);

    private static native long create(long seed, int type, boolean isDefault);

    public static native void delete(long random_ptr);

    public static int nextInt(long random_ptr) {
        return nextIntN(random_ptr, 0, 0);
    }

    public static int nextInt(long random_ptr, int bound) {
        return nextIntN(random_ptr, bound, 1);
    }

    public static int nextInt(long random_ptr, int from, int to) {
        return nextIntBetweenInclusive(random_ptr, from, to);
    }

    private static native int nextIntN(long random_ptr, int bound, int type);

    private static native int nextIntBetweenInclusive(long random_ptr, int from, int to);

    public static native long nextLong(long random_ptr);

    public static native boolean nextBoolean(long random_ptr);

    public static native float nextFloat(long random_ptr);

    public static native double nextDouble(long random_ptr);

    public static native double nextGaussian(long random_ptr);

    public static native int nextIntDirect(long random_ptr);

    public static native void setGlobalXoroshiroSeed(long seed);

    public static native void printSeed(long random_ptr);
}
