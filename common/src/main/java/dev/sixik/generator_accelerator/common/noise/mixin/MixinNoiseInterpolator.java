package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.noise.NoiseChunkPatch;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.*;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinNoiseInterpolator implements
        DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {

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

//    @Inject(method = "<init>", at = @At("RETURN"))
//    private void bts$onInit(NoiseChunk chunk, DensityFunction function, CallbackInfo ci) {
//        int sizeY = chunk.cellCountY + 1;
//        int sizeXZ = chunk.cellCountXZ + 1;
//
//        this.flat0 = new double[sizeXZ * sizeY];
//        this.flat1 = new double[sizeXZ * sizeY];
//    }

//    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;allocateSlice(II)[[D"))
//    private double[][] bts$allocate(NoiseChunk.NoiseInterpolator instance, int i, int j) {
//        return BTS$EMPTY;
//    }
//
//    /**
//     * @author Sixik
//     * @reason L1-Cache Friendly Flat Array Read (Zero pointer chasing)
//     */
//    @Overwrite
//    public void selectCellYZ(int y, int xz) {
//        int sizeY = this.field_34622.cellCountY + 1;
//
//        int idx0 = xz * sizeY + y;
//        int idx1 = (xz + 1) * sizeY + y;
//
//        this.noise000 = this.flat0[idx0];
//        this.noise010 = this.flat0[idx0 + 1];
//        this.noise100 = this.flat1[idx0];
//        this.noise110 = this.flat1[idx0 + 1];
//
//        this.noise001 = this.flat0[idx1];
//        this.noise011 = this.flat0[idx1 + 1];
//        this.noise101 = this.flat1[idx1];
//        this.noise111 = this.flat1[idx1 + 1];
//    }
//
//    /**
//     * @author Sixik
//     * @reason Swap flat arrays instead of 2D arrays
//     */
//    @Overwrite
//    public final void swapSlices() {
//        double[] temp = this.flat0;
//        this.flat0 = this.flat1;
//        this.flat1 = temp;
//    }

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
}
