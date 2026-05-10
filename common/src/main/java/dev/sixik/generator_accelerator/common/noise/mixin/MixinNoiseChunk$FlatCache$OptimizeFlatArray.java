package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.common.noise.NoiseChunk$FlatCache$FlatArray;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.FlatCache.class)
public abstract class MixinNoiseChunk$FlatCache$OptimizeFlatArray implements DensityFunctions.MarkerOrMarked,
        NoiseChunk.NoiseChunkDensityFunction, NoiseChunk$FlatCache$FlatArray {

    @Shadow
    @Final
    NoiseChunk field_36611;

    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Shadow
    @Final
    private double[][] values;

    @Unique
    private double[] bts$array;

    @Override
    public double[] bts$getArray() {
        return bts$array;
    }

    @Override
    public void bts$setArray(double[] value) {
        this.bts$array = value;
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;noiseSizeXZ:I",
                    opcode = Opcodes.GETFIELD)
    )
    public int bts$init$recirect_for_0(
            NoiseChunk instance,
            NoiseChunk noiseChunk,
            DensityFunction densityFunction,
            boolean bl) {
        return bl ? instance.noiseSizeXZ : -1;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(NoiseChunk noiseChunk, DensityFunction densityFunction, boolean bl, CallbackInfo ci) {
        if (bl) {
            final int sizeXZ = field_36611.noiseSizeXZ;
            final int side = sizeXZ + 1;

            this.bts$array = new double[side * side];
            final double[] flatValues = this.bts$array;
            final double[][] vanillaValues = this.values;

            for (int l = 0; l <= sizeXZ; l++) {
                int rowOffset = l * side;
                double[] row = vanillaValues[l];

                for (int o = 0; o <= sizeXZ; o++) {
                    flatValues[rowOffset + o] = row[o];
                }
            }
        }
    }

    /**
     * @author Sixik
     * @reason Use flat array
     */
    @Overwrite
    public double compute(FunctionContext functionContext) {
        final int side = field_36611.noiseSizeXZ + 1;

        final int k = (functionContext.blockX() >> 2) - field_36611.firstNoiseX;
        final int l = (functionContext.blockZ() >> 2) - field_36611.firstNoiseZ;

        if (k >= 0 && l >= 0 && k < side && l < side) {
            double[] flat = this.bts$array;
            if (flat != null && flat.length >= side * side) {
                return flat[k * side + l];
            }
        }

        return this.noiseFiller.compute(functionContext);
    }
}
