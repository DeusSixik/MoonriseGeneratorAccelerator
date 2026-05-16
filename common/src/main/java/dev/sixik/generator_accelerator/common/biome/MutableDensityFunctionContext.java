package dev.sixik.generator_accelerator.common.biome;

import net.minecraft.world.level.levelgen.DensityFunction;

public final class MutableDensityFunctionContext implements DensityFunction.FunctionContext {
    private int blockX;
    private int blockY;
    private int blockZ;

    public MutableDensityFunctionContext set(int blockX, int blockY, int blockZ) {
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        return this;
    }

    @Override
    public int blockX() {
        return blockX;
    }

    @Override
    public int blockY() {
        return blockY;
    }

    @Override
    public int blockZ() {
        return blockZ;
    }
}
