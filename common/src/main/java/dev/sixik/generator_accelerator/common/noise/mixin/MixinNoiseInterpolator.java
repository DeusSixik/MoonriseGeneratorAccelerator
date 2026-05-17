package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.noise.NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$InterpolatorSoA;
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

    @Shadow @Final
    NoiseChunk field_34622;
    @Shadow
    double[][] slice0;
    @Shadow
    double[][] slice1;
    @Shadow
    private double noise000;
    @Shadow
    private double noise001;
    @Shadow
    private double noise100;
    @Shadow
    private double noise101;
    @Shadow
    private double noise010;
    @Shadow
    private double noise011;
    @Shadow
    private double noise110;
    @Shadow
    private double noise111;
    @Shadow
    private double value;

    @Unique
    private int bts$soaIndex = -1;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;allocateSlice(II)[[D"))
    private double[][] bts$allocate(NoiseChunk.NoiseInterpolator instance, int cellCountY, int cellCountXZ) {
        return new double[cellCountXZ + 1][cellCountY + 1];
    }

    /**
     * @author Sixik
     * @reason Keep vanilla fallback valid when a path touches slices before SoA init
     */
    @Overwrite
    public void selectCellYZ(int pY, int pZ) {
        this.noise000 = this.slice0[pZ][pY];
        this.noise001 = this.slice0[pZ + 1][pY];
        this.noise100 = this.slice1[pZ][pY];
        this.noise101 = this.slice1[pZ + 1][pY];
        this.noise010 = this.slice0[pZ][pY + 1];
        this.noise011 = this.slice0[pZ + 1][pY + 1];
        this.noise110 = this.slice1[pZ][pY + 1];
        this.noise111 = this.slice1[pZ + 1][pY + 1];
    }

    /**
     * @author Sixik
     * @reason Keep vanilla fallback valid when a path touches slices before SoA init
     */
    @Overwrite
    public final void swapSlices() {
        double[][] tmp = this.slice0;
        this.slice0 = this.slice1;
        this.slice1 = tmp;
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

        if (!chunk.fillingCell) {
            return this.value;
        }

        final double deltaX = (double) chunk.inCellX / (double) chunk.cellWidth;
        final double deltaY = (double) chunk.inCellY / (double) chunk.cellHeight;
        final double deltaZ = (double) chunk.inCellZ / (double) chunk.cellWidth;

        final double lerpY00 = noise000 + deltaY * (noise010 - noise000);
        final double lerpY10 = noise100 + deltaY * (noise110 - noise100);
        final double lerpY01 = noise001 + deltaY * (noise011 - noise001);
        final double lerpY11 = noise101 + deltaY * (noise111 - noise101);

        final double lerpX0 = lerpY00 + deltaX * (lerpY10 - lerpY00);
        final double lerpX1 = lerpY01 + deltaX * (lerpY11 - lerpY01);

        return lerpX0 + deltaZ * (lerpX1 - lerpX0);
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
