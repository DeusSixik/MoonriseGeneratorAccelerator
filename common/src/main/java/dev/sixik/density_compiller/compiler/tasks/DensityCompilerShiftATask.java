package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftATask extends DensityCompilerShiftTaskBase<DensityFunctions.ShiftA> {
    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.ShiftA node) {
        return node.offsetNoise();
    }

    @Override
    protected void generateCoordinates(MethodVisitor mv, PipelineAsmContext ctx) {
        // X * 0.25
        genCoord(mv, ctx, "blockX");

        // 0.0
        mv.visitInsn(DCONST_0);

        // Z * 0.25
        genCoord(mv, ctx, "blockZ");
    }
}
