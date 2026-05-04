package dev.sixik.generator_accelerator.mixins.common_mixin.noise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;


@Mixin(NoiseChunk.Cache2D.class)
public class MixinNoiseChunk$Cache2D {

    @Shadow
    private long lastPos2D;
    @Shadow private double lastValue;
    @Shadow @Final
    private DensityFunction function;

    /**
     * @author Sixik
     * @reason Inline ChunkPos.asLong
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        final int x = ctx.blockX();
        final int z = ctx.blockZ();

        final long key = (long)x & 0xFFFFFFFFL | ((long)z << 32);

        if (this.lastPos2D == key) {
            return this.lastValue;
        } else {
            this.lastPos2D = key;
            final double val = this.function.compute(ctx);
            this.lastValue = val;
            return val;
        }
    }

}
