package dev.sixik.generator_accelerator.math.c3;

public class NativeNoiseChunk {

    public static native void fillNoiseArrayDirectly(
            long nativeNoisePtr,
            double[] ds,
            int startX, int startY, int startZ,
            int cellW, int cellH,
            double xzScale, double yScale
    );
}
