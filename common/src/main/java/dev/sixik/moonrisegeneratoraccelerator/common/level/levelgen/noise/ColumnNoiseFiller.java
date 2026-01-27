package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise;

public interface ColumnNoiseFiller {

    void fillColumn(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double additionalScale);

    default void fillColumnWithFactor(double[] values, int x, int z, int yStart, int yCount, double scaleX, double scaleY, double scaleZ, double valueFactor) {}

    default void fillNoiseColumn(double[] buffer, int x, int z, int yStart, int count,
                                 double scaleX, double scaleY, double scaleZ, double amplitude) { }

    default void fillNoiseColumnWithFactor(double[] buffer, int x, int z, int yStart, int count,
                                 double scaleX, double scaleY, double scaleZ, double amplitude, double valueFactor) { }
}
