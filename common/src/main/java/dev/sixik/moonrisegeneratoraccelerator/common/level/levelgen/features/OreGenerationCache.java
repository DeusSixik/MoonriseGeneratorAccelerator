package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.features;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.BitSet;
import java.util.function.IntFunction;

public class OreGenerationCache {

    public static final OreGenerationCache CACHE = new OreGenerationCache();

    protected static final IntFunction<BitSet> BIT_SET_CONSTRUCTOR = BitSet::new;

    protected ThreadLocal<Int2ObjectOpenHashMap<BitSet>> BITSETS = ThreadLocal.withInitial(Int2ObjectOpenHashMap::new);

    protected OreGenerationCache() {}

    public BitSet getOrCreate(int bits) {
        final BitSet bitSet = BITSETS.get().computeIfAbsent(bits, BIT_SET_CONSTRUCTOR);
        bitSet.clear();
        return bitSet;
    }
}
