package dev.sixik.moonrisegeneratoraccelerator.common.level.biome;

public class TargetPointBuffer {
    private static final ThreadLocal<long[]> BUFFER = ThreadLocal.withInitial(() -> new long[7]);

    public static long[] getAndSet(long t, long h, long c, long e, long d, long w) {
        long[] buf = BUFFER.get();
        buf[0] = t;
        buf[1] = h;
        buf[2] = c;
        buf[3] = e;
        buf[4] = d;
        buf[5] = w;
        buf[6] = 0L; // offset is usually 0 for target
        return buf;
    }
}
