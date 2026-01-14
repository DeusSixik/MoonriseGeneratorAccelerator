package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerFastAddTask extends DensityCompilerTask<DensitySpecializations.FastAdd> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastAdd function, PipelineAsmContext context) {
        context.visitNodeCompute(function.a());
        context.visitNodeCompute(function.b());
        visitor.visitInsn(DADD);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensitySpecializations.FastAdd node, DensityCompilerContext ctx, int destArrayVar) {
        ctx.compileNodeFill(node.a(), destArrayVar);

        int tempArrayVar = ctx.allocateTempBuffer();
        ctx.compileNodeFill(node.b(), tempArrayVar);

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2); // Preparing the stack for DASTORE (Array, Index)

            mv.visitInsn(DALOAD); // Loading a[i]

            mv.visitVarInsn(ALOAD, tempArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // Loading b[i]

            mv.visitInsn(DADD);    // Adding up
            mv.visitInsn(DASTORE); // Saving it in ds[i]
        });
    }
}
