package dev.sixik.generator_accelerator.common.noise.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public class MixinNoiseChunk$Cache2D {

    @Shadow
    private long lastPos2D;
    @Shadow private double lastValue;
    @Shadow @Final
    private DensityFunction function;

    /**
     * @author Sixik
     * @reason Inline ChunkPos.asLong and publish the one-slot cache under a monitor.
     */
    @Overwrite
    public synchronized double compute(DensityFunction.FunctionContext ctx) {
        final int x = ctx.blockX();
        final int z = ctx.blockZ();

        final long key = (long)x & 0xFFFFFFFFL | ((long)z << 32);

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
