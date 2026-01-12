package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseChunkPatch;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinNoiseInterpolator implements DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {

    @Shadow @Final
    NoiseChunk field_34622;

    @Shadow private double value;
    @Shadow private double noise000;
    @Shadow private double noise100;
    @Shadow private double noise010;
    @Shadow
    private double noise110;
    @Shadow private double noise001;
    @Shadow private double noise101;
    @Shadow private double noise011;
    @Shadow private double noise111;

    /**
     * @author Sixik
     * @reason Optimize lerp3 by removing division and nested method calls.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        if (ctx != field_34622) {
            return wrapped().compute(ctx);
        }

        final NoiseChunk chunk = field_34622;

        /*
            If we don't interpolate (a rare case of error)
         */
        if (!chunk.interpolating) {
            throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
        }

        /*
            If we just interpolate (an ordinary terrane)
         */
        if (!chunk.fillingCell) {
            return this.value;
        }

        final double invW = ((NoiseChunkPatch) chunk).bts$getInverseCellWidth();
        final double invH = ((NoiseChunkPatch) chunk).bts$getInverseCellHeight();

        final double deltaX = chunk.inCellX * invW;
        final double deltaY = chunk.inCellY * invH;
        final double deltaZ = chunk.inCellZ * invW;

        // Lerp Y (4 times)
        final double lerpY00 = noise000 + deltaY * (noise010 - noise000);
        final double lerpY10 = noise100 + deltaY * (noise110 - noise100);
        final double lerpY01 = noise001 + deltaY * (noise011 - noise001);
        final double lerpY11 = noise101 + deltaY * (noise111 - noise101);

        // Lerp X (2 times)
        final double lerpX0 = lerpY00 + deltaX * (lerpY10 - lerpY00);
        final double lerpX1 = lerpY01 + deltaX * (lerpY11 - lerpY01);

        // Lerp Z (Final)
        return lerpX0 + deltaZ * (lerpX1 - lerpX0);
    }
}
