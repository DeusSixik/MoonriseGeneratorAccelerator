package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.*;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinNoiseChunk$NoiseInterpolator$optimizeLogic
        implements DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {

    @Shadow
    @Final
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
    @Final
    private DensityFunction noiseFiller;

    @Shadow
    private double value;

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void selectCellYZ(int y, int xz) {
        /*
            Use cache for
         */
        final double[] s0_j0 = this.slice0[xz];
        final double[] s0_j1 = this.slice0[xz + 1];
        final double[] s1_j0 = this.slice1[xz];
        final double[] s1_j1 = this.slice1[xz + 1];

        final int y1 = y + 1;

        this.noise000 = s0_j0[y];
        this.noise001 = s0_j1[y];
        this.noise100 = s1_j0[y];
        this.noise101 = s1_j1[y];

        this.noise010 = s0_j0[y1];
        this.noise011 = s0_j1[y1];
        this.noise110 = s1_j0[y1];
        this.noise111 = s1_j1[y1];
    }

    /**
     * @author Sixik
     * @reason Inline and optimize lerp3 by removing unnecessary method calls
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        if (ctx != field_34622)
            return noiseFiller.compute(ctx);

        final NoiseChunk field_34622 = this.field_34622;

        if (field_34622.fillingCell) {
            final double tx = (double)field_34622.inCellX / (double)field_34622.cellWidth;
            final double ty = (double)field_34622.inCellY / (double)field_34622.cellHeight;
            final double tz = (double)field_34622.inCellZ / (double)field_34622.cellWidth;

            final double noise000 = this.noise000;
            final double noise001 = this.noise001;
            final double noise010 = this.noise010;
            final double noise011 = this.noise011;

            final double d00 = noise000 + tx * (noise100 - noise000);
            final double d01 = noise001 + tx * (noise101 - noise001);
            final double d10 = noise010 + tx * (noise110 - noise010);
            final double d11 = noise011 + tx * (noise111 - noise011);

            final double d0 = d00 + ty * (d10 - d00);
            final double d1 = d01 + ty * (d11 - d01);

            return d0 + tz * (d1 - d0);
        }
        return this.value;
    }
}
