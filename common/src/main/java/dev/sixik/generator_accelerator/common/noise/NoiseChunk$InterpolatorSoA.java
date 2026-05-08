package dev.sixik.generator_accelerator.common.noise;

/**
 * Chunk-owned structure-of-arrays state for {@code NoiseInterpolator}.
 */
public interface NoiseChunk$InterpolatorSoA {

    double bts$getInterpolatorValue(int index);

    double bts$getInterpolatorFillingValue(int index);
}
