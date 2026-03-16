package dev.sixik.generator_accelerator.common.biome;

public interface ClimateParameterListPrimitiveSearch<T> {

    T bts$findValue(final long[] values);

    FlatClimateIndex<T> bts$getClimateIndex();
}
