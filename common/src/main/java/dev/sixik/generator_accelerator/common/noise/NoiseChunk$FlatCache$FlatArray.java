package dev.sixik.generator_accelerator.common.noise;

public interface NoiseChunk$FlatCache$FlatArray {

    double[] bts$getArray();

    void bts$setArray(double[] value);

    void bts$copyFlatArrayToVanillaValues();

    default int bts$getSide() {
        return -1;
    }

    default int bts$getFirstNoiseX() {
        return Integer.MIN_VALUE;
    }

    default int bts$getFirstNoiseZ() {
        return Integer.MIN_VALUE;
    }
}
