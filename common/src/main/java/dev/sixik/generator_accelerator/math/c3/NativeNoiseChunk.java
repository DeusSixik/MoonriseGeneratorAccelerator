package dev.sixik.generator_accelerator.math.c3;

public class NativeNoiseChunk {

    public static native void fillSliceArrayDirectly(
            long noisePtr,
            double[] ds,
            int blockX, int blockZ,
            int cellNoiseMinY,
            int cellHeight,
            int cellCountY,
            double xzScale, double yScale
    );

    public static native void fillNoiseArrayDirectly(
            long nativeNoisePtr,
            double[] ds,
            int startX, int startY, int startZ,
            int cellW, int cellH,
            double xzScale, double yScale
    );
}
