package dev.sixik.generator_accelerator.common.beardifier.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Arrays;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker")
public abstract class MixinBeardifierMarker implements DfcCellFillAccess {

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        Arrays.fill(out, 0.0);
        chunk.arrayIndex = out.length;
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        chunk.arrayIndex = out.length;
    }
}
