package dev.sixik.generator_accelerator.common.biome.mixin.compats.terrablender;

import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.utils.ptr.LongPtr;
import net.minecraft.util.LinearCongruentialGenerator;
import org.spongepowered.asm.mixin.*;
import terrablender.core.TerraBlender;
import terrablender.worldgen.noise.AreaContext;

@CompatMixin(mod = TerraBlender.class)
@Mixin(value = AreaContext.class, remap = false)
public abstract class Terrablender$AreaContext$thread_local_random {
    @Shadow
    @Final
    private long seed;

    @Unique
    private final ThreadLocal<LongPtr> ga$rval = ThreadLocal.withInitial(LongPtr::new);

    /**
     * @author DenisMasterHerobrine
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
        this.ga$rval.get().value = value;
    }

    /**
     * @author DenisMasterHerobrine
     * @reason See {@link #initRandom(long, long)}.
     */
    @Overwrite(remap = false)
    public int nextRandom(int bound) {
        final LongPtr ptr = this.ga$rval.get();
        long local = ptr.value;
        int value = Math.floorMod(local >> 24, bound);
        ptr.value = LinearCongruentialGenerator.next(local, this.seed);
        return value;
    }
}
