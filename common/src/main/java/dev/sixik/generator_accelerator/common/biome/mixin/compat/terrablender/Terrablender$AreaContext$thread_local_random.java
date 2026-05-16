package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import net.minecraft.util.LinearCongruentialGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import terrablender.worldgen.noise.AreaContext;

@Mixin(value = AreaContext.class, remap = false)
public abstract class Terrablender$AreaContext$thread_local_random {
    @Shadow
    @Final
    private long seed;

    @Unique
    private final ThreadLocal<long[]> ga$rval = ThreadLocal.withInitial(() -> new long[1]);

    /**
     * @author Sixik
     * @reason GA can query TerraBlender uniqueness from several worldgen threads at once.
     * AreaContext stores its RNG cursor in an instance field, so make the cursor per-thread
     * before the Area cache lock is removed.
     */
    @Overwrite(remap = false)
    public void initRandom(long x, long y) {
        long value = this.seed;
        value = LinearCongruentialGenerator.next(value, x);
        value = LinearCongruentialGenerator.next(value, y);
        value = LinearCongruentialGenerator.next(value, x);
        value = LinearCongruentialGenerator.next(value, y);
        this.ga$rval.get()[0] = value;
    }

    /**
     * @author Sixik
     * @reason See {@link #initRandom(long, long)}.
     */
    @Overwrite(remap = false)
    public int nextRandom(int bound) {
        long[] local = this.ga$rval.get();
        int value = Math.floorMod(local[0] >> 24, bound);
        local[0] = LinearCongruentialGenerator.next(local[0], this.seed);
        return value;
    }
}
