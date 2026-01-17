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
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input());
        machine.popStack();

        // ОПТИМИЗАЦИЯ 1: ABS не нужен, если мин. значение >= 0
        if (node.type() == DensityFunctions.Mapped.Type.ABS && node.input().minValue() >= 0.0) {
            return;
        }

        // ОПТИМИЗАЦИЯ 2: HALF/QUARTER не нужны, если мин. значение >= 0 (всегда положительно)
        if ((node.type() == DensityFunctions.Mapped.Type.HALF_NEGATIVE || node.type() == DensityFunctions.Mapped.Type.QUARTER_NEGATIVE)
                && node.input().minValue() >= 0.0) {
            return;
        }

        ctx.comment("Mapped: " + node.type().name());
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
            case HALF_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.5, input);
            case QUARTER_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.25, input);
            case SQUEEZE -> {
                boolean needsClamp = input.minValue() < -1.0 || input.maxValue() > 1.0;
                DensityCompilerUtils.compileSqueeze(mv, ctx, needsClamp);
            }
        }
    }
}
