package dev.sixik.moonrisegeneratoraccelerator.common.level.biome;

public interface ClimateParameterListPrimitiveSearch<T> {

    T bts$findValue(final long[] values);

    FlatClimateIndex<T> bts$getClimateIndex();
}
