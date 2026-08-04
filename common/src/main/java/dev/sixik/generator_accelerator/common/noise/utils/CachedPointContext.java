package dev.sixik.generator_accelerator.common.noise.utils;

import net.minecraft.world.level.levelgen.DensityFunction;

public final class CachedPointContext implements DensityFunction.FunctionContext {

    private int x, y, z;

    public CachedPointContext() { }

    public CachedPointContext update(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    @Override
    public int blockX() {
        return x;
    }

    @Override
    public int blockY() {
        return y;
    }

    @Override
    public int blockZ() {
        return z;
    }
}
