package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseChunk$FlatCache$FlatArray;
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
    public int bts$init$recirect_for_0(NoiseChunk instance) {
        return -1;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(NoiseChunk noiseChunk, DensityFunction densityFunction, boolean bl, CallbackInfo ci) {
        if (bl) {
            final int sizeXZ = field_36611.noiseSizeXZ;
            final int side = sizeXZ + 1;

            this.bts$array = new double[side * side];
            final double[] values = this.bts$array;

            int fX = field_36611.firstNoiseX;
            int fZ = field_36611.firstNoiseZ;

            for (int l = 0; l <= sizeXZ; l++) {
                int blockX = (fX + l) << 2;
                int rowOffset = l * side;

                for (int o = 0; o <= sizeXZ; o++) {
                    int blockZ = (fZ + o) << 2;
                    values[rowOffset + o] = densityFunction.compute(new DensityFunction.SinglePointContext(blockX, 0, blockZ));
                }
            }
        }
    }

    /**
     * @author Sixik
     * @reason Use flat array
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext functionContext) {
        final int side = field_36611.noiseSizeXZ + 1;

        final int k = (functionContext.blockX() >> 2) - field_36611.firstNoiseX;
        final int l = (functionContext.blockZ() >> 2) - field_36611.firstNoiseZ;

        if (k >= 0 && l >= 0 && k < side && l < side) {
            return bts$array[k * side + l];
        }

        return this.noiseFiller.compute(functionContext);
    }
}
