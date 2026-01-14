package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.DALOAD;
import static org.objectweb.asm.Opcodes.DASTORE;
import static org.objectweb.asm.Opcodes.ILOAD;

public class DensityCompilerFastMinTask extends DensityCompilerTask<DensitySpecializations.FastMin> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastMin function, PipelineAsmContext context) {
        context.visitNodeCompute(function.a());
        context.visitNodeCompute(function.b());
        DensityCompilerUtils.min(visitor);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensitySpecializations.FastMin node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.a(), destArrayVar);

        int tempArrayVar = ctx.allocateTempBuffer();
        ctx.visitNodeFill(node.b(), tempArrayVar);

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2); // Preparing the stack for DASTORE (Array, Index)

            mv.visitInsn(DALOAD); // Loading a[i]

            mv.visitVarInsn(ALOAD, tempArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // Loading b[i]

            DensityCompilerUtils.min(mv); // Min up

            mv.visitInsn(DASTORE); // Saving it in ds[i]
        });
    }
}
