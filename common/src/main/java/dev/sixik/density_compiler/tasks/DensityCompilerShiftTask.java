package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.commons.GeneratorAdapter;

public class DensityCompilerShiftTask extends DensityCompilerShiftTaskBase<DensityFunctions.Shift> {
    @Override
    protected void generateCoordinates(GeneratorAdapter ga, DCAsmContext ctx) {
        genCoord(ga, ctx, "blockX");
        genCoord(ga, ctx, "blockY");
        genCoord(ga, ctx, "blockZ");
    }

    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.Shift node) {
        return node.offsetNoise();
    }
}
