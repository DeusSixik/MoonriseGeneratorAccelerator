package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
    }

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());

        if (node.type() == DensityFunctions.Mapped.Type.ABS && node.input().minValue() >= 0.0) {
            return;
        }

        generateTransformMath(mv, node.type(), node.input(), ctx);
    }

    private void generateTransformMath(MethodVisitor mv, DensityFunctions.Mapped.Type type, DensityFunction input, PipelineAsmContext ctx) {
        switch (type) {
            case ABS -> {
                DensityCompilerUtils.abs(mv);
            }
            case SQUARE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
            }
            case CUBE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
                mv.visitInsn(DMUL);
            }
            case HALF_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.5);
            case QUARTER_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.25);
            case SQUEEZE -> {
                boolean needsClamp = input.minValue() < -1.0 || input.maxValue() > 1.0;
                DensityCompilerUtils.compileSqueeze(mv, ctx, needsClamp);
            }
        }
    }
}
