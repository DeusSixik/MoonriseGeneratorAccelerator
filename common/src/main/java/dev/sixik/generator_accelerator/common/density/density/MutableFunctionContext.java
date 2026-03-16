package dev.sixik.generator_accelerator.common.density.density;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

public class MutableFunctionContext implements DensityFunction.FunctionContext {
    private int x, y, z;
    private final Blender blender;

    public MutableFunctionContext(Blender blender) {
        this.blender = blender;
    }

    public void set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override public int blockX() { return x; }
    @Override public int blockY() { return y; }
    @Override public int blockZ() { return z; }
    @Override public Blender getBlender() { return blender; }
}