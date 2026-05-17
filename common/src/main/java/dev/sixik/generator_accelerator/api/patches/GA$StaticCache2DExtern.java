package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.util.StaticCache2D;

public interface GA$StaticCache2DExtern<T> {

    static <T> GA$StaticCache2DExtern<T> get(StaticCache2D<T> cache2D) {
        return (GA$StaticCache2DExtern<T>) cache2D;
    }

    T ga$getFast(int index);

    int ga$getIndex(int x, int z);

    int ga$getX(int x);

    int ga$getZ(int z);

    int ga$offset();

    int ga$shift();

    Object[] ga$getRawData();
}
