package dev.sixik.generator_accelerator.api.patches;

public interface GA$NoiseChunk$NoiseInterpolatorPatch {

    double[] bts$getSlice0();

    double[] bts$getSlice1();

    void bts$setSoAIndex(int index);

    int bts$getSoAIndex();

    void bts$copyData(double[] newArray, boolean pIsSlice0, int startIndex, int sizeY);

}
