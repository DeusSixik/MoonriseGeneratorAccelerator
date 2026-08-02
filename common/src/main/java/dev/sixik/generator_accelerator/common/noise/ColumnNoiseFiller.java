package dev.sixik.generator_accelerator.common.noise;

public interface ColumnNoiseFiller {

    void fillColumn(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double outputFactor);

    default void fillColumnWithFactor(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double outputFactor) {}

    default void addColumnWithFactor(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double outputFactor) {}

    default void fillNoiseColumn(double[] buffer, int x, int z, int yStart, int count,
                                 double scaleX, double scaleY, double scaleZ, double amplitude) { }

    default void fillNoiseColumnWithFactor(double[] buffer, int x, int z, int yStart, int count,
                                 double scaleX, double scaleY, double scaleZ, double amplitude, double valueFactor) { }
}
