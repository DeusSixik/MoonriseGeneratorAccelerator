package dev.sixik.generator_accelerator.common.density.density_custom;

public class DensityThreadLocalData {

    private static final ThreadLocal<Pool> THREAD_POOL = ThreadLocal.withInitial(Pool::new);

    private static class Pool {
        double[][] arrays = new double[64][];
        int depth = 0;

        double[] acquire(int length) {
            int d = depth;
            depth = d + 1;

            if (d >= arrays.length) {
                arrays = java.util.Arrays.copyOf(arrays, arrays.length * 2);
            }

            double[] ds = arrays[d];
            if (ds == null || ds.length < length) {
                ds = new double[length];
                arrays[d] = ds;
            }
            return ds;
        }

        void release() {
            depth--;
        }
    }

    public static double[] acquire(int length) {
        return THREAD_POOL.get().acquire(length);
    }

    public static void release() {
        THREAD_POOL.get().release();
    }
}
