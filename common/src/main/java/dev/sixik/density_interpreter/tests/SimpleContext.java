package dev.sixik.density_interpreter.tests;

import net.minecraft.world.level.levelgen.DensityFunction;

public class SimpleContext implements DensityFunction.FunctionContext {

    private int x, y, z;

    public SimpleContext() {

    }

    public void update(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
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
