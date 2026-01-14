package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());

        Label labelPopAndOut = new Label();
        Label labelOut = new Label();
        Label labelEnd = new Label();

        mv.visitInsn(DUP2);                   // Stack: [input, input]
        mv.visitLdcInsn(node.minInclusive()); // Stack: [input, input, min]
        mv.visitInsn(DCMPL);                  // Stack: [input, res_int]
        mv.visitJumpInsn(IFLT, labelPopAndOut); // input < min

        mv.visitLdcInsn(node.maxExclusive()); // Stack: [input, max]
        mv.visitInsn(DCMPG);                  // Stack: [res_int]
        mv.visitJumpInsn(IFGE, labelOut);     // input >= max

        ctx.visitNodeCompute(node.whenInRange()); // Stack: [res_in]
        mv.visitJumpInsn(GOTO, labelEnd);

        mv.visitLabel(labelPopAndOut);
        mv.visitInsn(POP2);

        mv.visitLabel(labelOut);
        ctx.visitNodeCompute(node.whenOutOfRange()); // Stack: [res_out]

        mv.visitLabel(labelEnd);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.input(), destArrayVar);

        double min = node.minInclusive();
        double max = node.maxExclusive();

        ctx.arrayForI(destArrayVar, (iVar) -> {
            Label labelOutOfRange = new Label();
            Label labelEnd = new Label();

            /*
                Creating a context in a NEW slot (for example, 10 or 11)
             */
            int varContext = ctx.newLocalInt();
            mv.visitVarInsn(ALOAD, 2); // Provider
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitVarInsn(ASTORE, varContext);

            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD);

            mv.visitInsn(DUP2);
            mv.visitLdcInsn(min);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFLT, labelOutOfRange);

            mv.visitInsn(DUP2);
            mv.visitLdcInsn(max);
            mv.visitInsn(DCMPG);
            mv.visitJumpInsn(IFGE, labelOutOfRange);

            /*
                In Range
             */
            mv.visitInsn(POP2);
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(varContext); // Подменяем слот для вложенных нод
            ctx.visitNodeCompute(node.whenInRange());
            ctx.setCurrentContextVar(oldCtx); // Возвращаем назад

            mv.visitInsn(DASTORE);
            mv.visitJumpInsn(GOTO, labelEnd);

            /*
                Out of Range
             */
            mv.visitLabel(labelOutOfRange);
            mv.visitInsn(POP2);
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            ctx.setCurrentContextVar(varContext);
            ctx.visitNodeCompute(node.whenOutOfRange());
            ctx.setCurrentContextVar(oldCtx);

            mv.visitInsn(DASTORE);
            mv.visitLabel(labelEnd);
        });
    }
}
