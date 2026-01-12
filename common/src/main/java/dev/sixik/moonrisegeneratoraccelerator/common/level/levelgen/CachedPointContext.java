package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;


import net.minecraft.world.level.levelgen.DensityFunction;

public class CachedPointContext implements DensityFunction.FunctionContext {

//    public static final ThreadLocal<CachedPointContext> CACHE = ThreadLocal.withInitial(CachedPointContext::new);

    protected int x, y, z;

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
