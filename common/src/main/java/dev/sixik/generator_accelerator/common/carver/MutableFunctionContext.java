package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.DensityFunction;

public final class MutableFunctionContext implements DensityFunction.FunctionContext {
    private int x;
    private int y;
    private int z;

    public void set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int blockX() {
        return this.x;
    }

    @Override
    public int blockY() {
        return this.y;
    }

    @Override
    public int blockZ() {
        return this.z;
    }
}
