package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.objectweb.asm.Opcodes.DCONST_0;

public class DensityCompilerShiftBTask extends DensityCompilerShiftTaskBase<DensityFunctions.ShiftB> {

    @Override
    protected void generateCoordinates(GeneratorAdapter ga, DCAsmContext ctx) {
        // Z * 0.25 (как первый аргумент X для шума)
        genCoord(ga, ctx, "blockZ");

        // X * 0.25 (как второй аргумент Y для шума)
        genCoord(ga, ctx, "blockX");

        // 0.0 (как третий аргумент Z для шума)
        ga.visitInsn(DCONST_0); // У тебя было DCONST_1, исправил на 0 по логике ShiftB
    }

    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.ShiftB node) {
        return node.offsetNoise();
    }
}
