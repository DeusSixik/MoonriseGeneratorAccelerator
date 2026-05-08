package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.noise.NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$InterpolatorSoA;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkPatch;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinNoiseInterpolator implements
        DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction, NoiseChunk$NoiseInterpolatorPatch {

    @Unique
    private static final double[][] BTS$EMPTY = new double[0][0];

    @Shadow @Final
    NoiseChunk field_34622;

    @Unique
    private int bts$soaIndex = -1;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;allocateSlice(II)[[D"))
    private double[][] bts$allocate(NoiseChunk.NoiseInterpolator instance, int i, int j) {
        return BTS$EMPTY;
    }

    /**
     * @author Sixik
     * @reason L1-Cache Friendly Flat Array Read (Zero pointer chasing)
     */
    @Overwrite
    public void selectCellYZ(int pY, int pZ) {
        throw new UnsupportedOperationException();
    }

    /**
     * @author Sixik
     * @reason Swap flat arrays instead of 2D arrays
     */
    @Overwrite
    public final void swapSlices() {
        throw new UnsupportedOperationException();
    }

    /**
     * @author Sixik
     * @reason Optimize lerp3 by removing division and nested method calls.
     */
    @Overwrite
    public double compute(FunctionContext ctx) {
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

        final int soaIndex = this.bts$soaIndex;
        if (soaIndex >= 0 && chunk instanceof NoiseChunk$InterpolatorSoA soa) {
            if (chunk.fillingCell) {
                return soa.bts$getInterpolatorFillingValue(soaIndex);
            }
            return soa.bts$getInterpolatorValue(soaIndex);
        }

        throw new UnsupportedOperationException();
//
//        /*
//            Fallback for unexpected early calls before NoiseChunk assigned SoA
//            indices. Normal terrain goes through the chunk-owned arrays above.
//         */
//        if (!chunk.fillingCell) {
//            return this.value;
//        }
//
//        final double invW = ((NoiseChunkPatch) chunk).bts$getInverseCellWidth();
//        final double invH = ((NoiseChunkPatch) chunk).bts$getInverseCellHeight();
//
//        final double deltaX = chunk.inCellX * invW;
//        final double deltaY = chunk.inCellY * invH;
//        final double deltaZ = chunk.inCellZ * invW;
//
//        // Lerp Y (4 times)
//        final double lerpY00 = noise000 + deltaY * (noise010 - noise000);
//        final double lerpY10 = noise100 + deltaY * (noise110 - noise100);
//        final double lerpY01 = noise001 + deltaY * (noise011 - noise001);
//        final double lerpY11 = noise101 + deltaY * (noise111 - noise101);
//
//        // Lerp X (2 times)
//        final double lerpX0 = lerpY00 + deltaX * (lerpY10 - lerpY00);
//        final double lerpX1 = lerpY01 + deltaX * (lerpY11 - lerpY01);
//
//        // Lerp Z (Final)
//        return lerpX0 + deltaZ * (lerpX1 - lerpX0);
    }

    @Override
    public double[] bts$getSlice0() {
        throw new UnsupportedOperationException();
    }

    @Override
    public double[] bts$getSlice1() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void bts$setSoAIndex(int index) {
        this.bts$soaIndex = index;
    }

    @Override
    public int bts$getSoAIndex() {
        return this.bts$soaIndex;
    }

    @Override
    public void bts$copyData(double[] newArray, boolean pIsSlice0, int startIndex, int sizeY) {
        throw new UnsupportedOperationException();
    }
}
