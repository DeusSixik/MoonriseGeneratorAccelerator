package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.util.BitStorage;

/**
 * Internal hot-path view over Minecraft's packed {@code SimpleBitStorage}.
 *
 * <p>This is deliberately tiny: mixins expose the immutable packing metadata and
 * backing {@code long[]} so PalettedContainer bulk operations can scan packed
 * words directly without virtual {@code BitStorage#get(int)} calls or temporary
 * fastutil sets/maps.</p>
 */
public interface GA$SimpleBitStorageExtern {

    static GA$SimpleBitStorageExtern get(BitStorage storage) {
        return (GA$SimpleBitStorageExtern) storage;
    }

    long[] ga$getRaw();

    int ga$getBits();

    long ga$getMask();

    int ga$getSize();

    int ga$getValuesPerLong();
}
