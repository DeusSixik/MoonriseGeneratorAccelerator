package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import terrablender.worldgen.noise.Area;
import terrablender.worldgen.noise.PixelTransformer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@Mixin(value = Area.class, remap = false)
public abstract class Terrablender$Area$lock_free_cache {
    @Unique
    private static final VarHandle GA$LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);
    @Unique
    private static final VarHandle GA$INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    @Unique
    private static final long GA$UPDATING_KEY = Long.MIN_VALUE;
    @Unique
    private Object[] ga$locks;

    @Shadow
    @Final
    private long[] keys;
    @Shadow
    @Final
    private int[] values;
    @Shadow
    @Final
    private int mask;
    @Shadow
    @Final
    private PixelTransformer operator;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void ga$initStripedLocks(PixelTransformer operator, int size, CallbackInfo ci) {
        int stripes = Math.min(64, this.mask + 1);
        this.ga$locks = new Object[stripes];
        for (int i = 0; i < stripes; ++i) {
            this.ga$locks[i] = new Object();
        }
    }

    /**
     * @author Sixik
     * @reason TerraBlender serializes every uniqueness lookup through one StampedLock.
     * Keep hits lock-free and use tiny per-stripe monitors only for cache misses.
     */
    @Overwrite(remap = false)
    public int get(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        if (key == GA$UPDATING_KEY) {
            return this.operator.apply(x, z);
        }
        int idx = (int) HashCommon.mix(key) & this.mask;
        long cachedKey = (long) GA$LONG_ARRAY.getAcquire(this.keys, idx);
        if (cachedKey == key) {
            int cachedValue = (int) GA$INT_ARRAY.getAcquire(this.values, idx);
            if ((long) GA$LONG_ARRAY.getAcquire(this.keys, idx) == key) {
                return cachedValue;
            }
        }

        Object[] locks = this.ga$locks;
        synchronized (locks[idx & (locks.length - 1)]) {
            if ((long) GA$LONG_ARRAY.getAcquire(this.keys, idx) == key) {
                return (int) GA$INT_ARRAY.getAcquire(this.values, idx);
            }

            GA$LONG_ARRAY.setRelease(this.keys, idx, GA$UPDATING_KEY);
            int value = this.operator.apply(x, z);
            GA$INT_ARRAY.setRelease(this.values, idx, value);
            GA$LONG_ARRAY.setRelease(this.keys, idx, key);
            return value;
        }
    }
}
