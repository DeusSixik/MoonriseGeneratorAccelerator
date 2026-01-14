package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());

        switch (node.specificType()) {
            case MUL -> {
                mv.visitLdcInsn(node.argument());
                mv.visitInsn(DMUL);
            }
            case ADD -> {
                mv.visitLdcInsn(node.argument());
                mv.visitInsn(DADD);
            }
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.input(), destArrayVar);

        double arg = node.argument();
        int opcode = (node.specificType() == DensityFunctions.MulOrAdd.Type.MUL) ? DMUL : DADD;

        /*
            Optimization: if it's addition from 0 or multiplication by 1, we don't do anything.
         */
        if (opcode == DADD && arg == 0.0) return;
        if (opcode == DMUL && arg == 1.0) return;

        /*
            We perform the transformation in one cycle
         */
        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2); // Stack: [Array, Index, Array, Index]

            mv.visitInsn(DALOAD); // Stack: [Array, Index, Value]
            mv.visitLdcInsn(arg); // Stack: [Array, Index, Value, Argument]
            mv.visitInsn(opcode); // Stack: [Array, Index, newValue]

            mv.visitInsn(DASTORE);
        });
    }
}
