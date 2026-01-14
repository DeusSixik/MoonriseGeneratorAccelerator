package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerTwoArgumentSimpleFunctionTask extends
        DensityCompilerTask<DensityFunctions.TwoArgumentSimpleFunction> {

    @Override
    protected void compileCompute(MethodVisitor visitor,
                                  DensityFunctions.TwoArgumentSimpleFunction function,
                                  PipelineAsmContext context
    ) {
        context.visitNodeCompute(function.argument1());
        context.visitNodeCompute(function.argument2());
        switch (function.type()) {
            case ADD -> visitor.visitInsn(DADD);
            case MUL -> visitor.visitInsn(DMUL);
            case MIN -> DensityCompilerUtils.min(visitor);
            case MAX -> DensityCompilerUtils.max(visitor);
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.TwoArgumentSimpleFunction node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.argument1(), destArrayVar);

        /*
            Optimization for MUL (Short-circuiting)
            If this is a multiplication, we can use a lazy approach, as in Ap2,
            to not count the second argument where the first one = 0.0
         */
        if (node.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL) {
            ctx.arrayForI(destArrayVar, (iVar) -> {
                Label skip = new Label();
                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DALOAD);
                mv.visitInsn(DUP2);
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(IFEQ, skip); // If d == 0.0, skip the multiplication

                /*
                    Calculating the second argument for a specific point
                 */
                ctx.compileNodeComputeForIndex(mv, node.argument2(), iVar);
                mv.visitInsn(DMUL);

                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DUP2_X2);
                mv.visitInsn(POP2);
                mv.visitInsn(DASTORE);

                mv.visitLabel(skip);
                mv.visitInsn(POP2); // We remove the remaining 0.0 on the stack
            });
            return;
        }

        /*
            We use a temporary buffer for ADD, MIN, MAX
            This is faster than calling compute() in a loop for each point,
            as it allows the second argument to use its vector fillArray optimization.
         */
        int tempArrayVar = ctx.allocateTempBuffer();
        ctx.visitNodeFill(node.argument2(), tempArrayVar);

        int opcode = switch (node.type()) {
            case ADD -> DADD;
            default -> -1; // We use static calls for MIN/MAX.
        };

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2);
            mv.visitInsn(DALOAD); // arg1[i]

            mv.visitVarInsn(ALOAD, tempArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // arg2[i]

            if (opcode != -1) {
                mv.visitInsn(opcode);
            } else if (node.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.MIN) {
                DensityCompilerUtils.min(mv);
            } else {
                DensityCompilerUtils.max(mv);
            }

            mv.visitInsn(DASTORE);
        });
    }
}
