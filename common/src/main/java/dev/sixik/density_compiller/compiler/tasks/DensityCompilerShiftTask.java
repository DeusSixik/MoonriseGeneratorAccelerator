package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerShiftTask extends DensityCompilerShiftTaskBase<DensityFunctions.Shift> {
    @Override
    protected DensityFunction.NoiseHolder getHolder(DensityFunctions.Shift node) {
        return node.offsetNoise();
    }

    @Override
    protected void generateCoordinates(MethodVisitor mv, PipelineAsmContext ctx) {
        genCoord(mv, ctx, "blockX");
        genCoord(mv, ctx, "blockY");
        genCoord(mv, ctx, "blockZ");
    }
}
