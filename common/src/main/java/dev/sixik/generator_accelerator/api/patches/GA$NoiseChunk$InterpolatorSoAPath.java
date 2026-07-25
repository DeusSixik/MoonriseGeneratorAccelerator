package dev.sixik.generator_accelerator.api.patches;

public interface GA$NoiseChunk$InterpolatorSoAPath {

    double bts$getInverseCellWidth();

    double bts$getInverseCellHeight();

    double bts$getInterpolatorValue(int index);

    double bts$getInterpolatorFillingValue(int index);

    double bts$getInterpolatorFillingValue(int index, int inCellX, int inCellY, int inCellZ);

    double bts$getInterpolatorFillingValue(int index, double deltaX, double deltaY, double deltaZ);

    void bts$updateFillingY(double deltaY);

    void bts$updateFillingX(double deltaX);

    void bts$updateFillingZ(double deltaZ);

    double[] bts$getValueArray();

    double[] bts$getNoise000Array();

    double[] bts$getNoise100Array();

    double[] bts$getNoise010Array();

    double[] bts$getNoise110Array();

    double[] bts$getNoise001Array();

    double[] bts$getNoise101Array();

    double[] bts$getNoise011Array();

    double[] bts$getNoise111Array();
}
