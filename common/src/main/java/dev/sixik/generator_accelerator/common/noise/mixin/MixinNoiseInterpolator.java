package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.noise.NoiseChunk$NoiseInterpolatorPatch;
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

    @Shadow
    private double[][] slice1;
    @Unique
    private double[] bts$slice0;

    @Unique
    private double[] bts$slice1;

    @Unique
    private int bts$sizeY;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$onInit(NoiseChunk chunk, DensityFunction function, CallbackInfo ci) {
        bts$sizeY = chunk.cellCountY + 1;
        int sizeXZ = chunk.cellCountXZ + 1;

        this.bts$slice0 = new double[sizeXZ * bts$sizeY];
        this.bts$slice1 = new double[sizeXZ * bts$sizeY];
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;allocateSlice(II)[[D"))
    private double[][] bts$allocate(NoiseChunk.NoiseInterpolator instance, int i, int j) {
        return BTS$EMPTY;
    }

    @Unique
    private int bts$getIndex(int pZ, int pY) {
        return pZ * bts$sizeY + pY;
    }


    /**
     * @author Sixik
     * @reason L1-Cache Friendly Flat Array Read (Zero pointer chasing)
     */
    @Overwrite
    public void selectCellYZ(int pY, int pZ) {
        this.noise000 = this.bts$slice0[bts$getIndex(pZ, pY)];
        this.noise001 = this.bts$slice0[bts$getIndex(pZ + 1, pY)];
        this.noise100 = this.bts$slice1[bts$getIndex(pZ, pY)];
        this.noise101 = this.bts$slice1[bts$getIndex(pZ + 1, pY)];
        this.noise010 = this.bts$slice0[bts$getIndex(pZ, pY + 1)];
        this.noise011 = this.bts$slice0[bts$getIndex(pZ + 1, pY + 1)];
        this.noise110 = this.bts$slice1[bts$getIndex(pZ, pY + 1)];
        this.noise111 = this.bts$slice1[bts$getIndex(pZ + 1, pY + 1)];
    }

    /**
     * @author Sixik
     * @reason Swap flat arrays instead of 2D arrays
     */
    @Overwrite
    public final void swapSlices() {
        double[] adouble = this.bts$slice0;
        this.bts$slice0 = this.bts$slice1;
        this.bts$slice1 = adouble;
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

    @Override
    public double[] bts$getSlice0() {
        return bts$slice0;
    }

    @Override
    public double[] bts$getSlice1() {
        return bts$slice1;
    }

    @Override
    public void bts$copyData(double[] newArray, boolean pIsSlice0, int startIndex, int sizeY) {
        System.arraycopy(
                newArray,
                0,
                (pIsSlice0 ? bts$slice0 : bts$slice1),
                startIndex,
                sizeY
        );
    }
}
