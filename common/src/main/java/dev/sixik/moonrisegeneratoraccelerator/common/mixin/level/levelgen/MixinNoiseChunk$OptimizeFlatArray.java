package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseChunk$FlatCache$FlatArray;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseChunk.class)
public class MixinNoiseChunk$OptimizeFlatArray {

    @Shadow
    @Final
    private int noiseSizeXZ;
    @Shadow
    @Final
    private int firstNoiseX;
    @Shadow
    @Final
    private int firstNoiseZ;
    @Shadow
    @Final
    private Blender blender;

    @Shadow
    @Final
    private NoiseChunk.FlatCache blendAlpha;

    @Shadow
    @Final
    private NoiseChunk.FlatCache blendOffset;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;noiseSizeXZ:I",
                    ordinal = 0,
                    opcode = Opcodes.GETFIELD)
    )
    public int bts$init(NoiseChunk noiseChunk, int value) {
        bts$optimizeValues(noiseChunk);
        return -1;
    }

    @Unique
    private void bts$optimizeValues(NoiseChunk noiseChunk) {
        final int sizeXZ = this.noiseSizeXZ;
        final Blender blender = this.blender;

        int size = sizeXZ + 1;
        double[] flatAlpha = new double[size * size];
        double[] flatOffset = new double[size * size];

        int fX = this.firstNoiseX;
        int fZ = this.firstNoiseZ;

        for (int l = 0; l < sizeXZ; l++) {
            int m = fX + l;
            int blockX = m << 2;
            int rowOffset = l * size;

            for (int o = 0; o < sizeXZ; o++) {
                int p = fZ + o;
                int blockZ = p << 2;

                Blender.BlendingOutput blendingOutput = blender.blendOffsetAndFactor(blockX, blockZ);
                int index = rowOffset + o;
                flatAlpha[index] = blendingOutput.alpha();
                flatOffset[index] = blendingOutput.blendingOffset();
            }
        }

        ((NoiseChunk$FlatCache$FlatArray) blendAlpha).bts$setArray(flatAlpha);
        ((NoiseChunk$FlatCache$FlatArray) blendOffset).bts$setArray(flatOffset);
    }
}
