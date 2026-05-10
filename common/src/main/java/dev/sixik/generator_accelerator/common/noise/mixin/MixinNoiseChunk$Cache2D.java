package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcThreadOwnedCacheAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class MixinNoiseChunk$Cache2D implements DfcThreadOwnedCacheAccess {

    @Shadow
    private long lastPos2D;
    @Shadow private double lastValue;
    @Shadow @Final
    private DensityFunction function;

    @Unique
    private Thread bts$ownerThread;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$initOwnerThread(DensityFunction densityFunction, CallbackInfo ci) {
        this.bts$ownerThread = Thread.currentThread();
    }

    @Override
    public boolean dfc$isOwnedByCurrentThread() {
        return this.bts$ownerThread == Thread.currentThread();
    }

    /**
     * @author Sixik
     * @reason Inline ChunkPos.asLong and keep the mutable one-slot cache thread-owned.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        final int x = ctx.blockX();
        final int z = ctx.blockZ();

        final long key = (long)x & 0xFFFFFFFFL | ((long)z << 32);

        if (!dfc$isOwnedByCurrentThread()) {
            return this.function.compute(ctx);
        }

        if (this.lastPos2D == key) {
            return this.lastValue;
        } else {
            final double val = this.function.compute(ctx);
            this.lastValue = val;
            this.lastPos2D = key;
            return val;
        }
    }

}
