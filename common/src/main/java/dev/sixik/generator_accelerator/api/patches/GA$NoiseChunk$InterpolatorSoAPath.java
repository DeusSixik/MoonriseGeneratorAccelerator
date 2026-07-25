package dev.sixik.generator_accelerator.api.patches;

public interface GA$NoiseChunk$InterpolatorSoAPath {

    double bts$getInverseCellWidth();

    double bts$getInverseCellHeight();

    double bts$getInterpolatorValue(int index);

    double bts$getInterpolatorFillingValue(int index);

    double bts$getInterpolatorFillingValue(int index, int inCellX, int inCellY, int inCellZ);

    double bts$getInterpolatorFillingValue(int index, double deltaX, double deltaY, double deltaZ);
}
