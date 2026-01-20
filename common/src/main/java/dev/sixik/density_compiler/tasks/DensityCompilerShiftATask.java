package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.objectweb.asm.Opcodes.DCONST_0;

public class DensityCompilerShiftATask extends DensityCompilerShiftTaskBase<DensityFunctions.ShiftA> {

    @Override
    protected void generateCoordinates(GeneratorAdapter ga, DCAsmContext ctx) {
        // X * 0.25
        genCoord(ga, ctx, "blockX");

        // 0.0
        ga.visitInsn(DCONST_0);

        // Z * 0.25
        genCoord(ga, ctx, "blockZ");
    }

    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.ShiftA node) {
        return node.offsetNoise();
    }
}