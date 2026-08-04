package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface GA$NoiseChunk$NoiseInterpolatorPatch {

    DensityFunction bts$getNoiseFiller();

    void bts$setNoiseFiller(DensityFunction densityFunction);

    double[] bts$getSlice0();

    double[] bts$getSlice1();

    void bts$setSoAIndex(int index);

    int bts$getSoAIndex();

    void bts$copyData(double[] newArray, boolean pIsSlice0, int startIndex, int sizeY);

}
