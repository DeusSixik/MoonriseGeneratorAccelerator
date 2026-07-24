package dev.sixik.generator_accelerator.common.beardifier.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcZeroCellFillAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Arrays;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker")
public abstract class MixinBeardifierMarker implements DfcZeroCellFillAccess {

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        Arrays.fill(out, 0.0);
        chunk.inCellX = chunk.cellWidth - 1;
        chunk.inCellY = 0;
        chunk.inCellZ = chunk.cellWidth - 1;
        chunk.arrayIndex = out.length;
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        chunk.inCellX = chunk.cellWidth - 1;
        chunk.inCellY = 0;
        chunk.inCellZ = chunk.cellWidth - 1;
        chunk.arrayIndex = out.length;
    }
}
