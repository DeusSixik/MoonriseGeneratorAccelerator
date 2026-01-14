package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx) {
        // ... (оптимизации min/max те же) ...
        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        if (inMin >= min && inMax < max) { ctx.visitNodeCompute(node.whenInRange()); return; }
        if (inMax < min || inMin >= max) { ctx.visitNodeCompute(node.whenOutOfRange()); return; }

        ctx.visitNodeCompute(node.input()); // Stack: [input]

        Label labelOutOfRange = new Label();
        Label labelEnd = new Label();

        // Check Min
        mv.visitInsn(DUP2);                   // [input, input]
        mv.visitLdcInsn(min);                 // [input, input, min]
        mv.visitInsn(DCMPL);                  // [input, res]
        mv.visitJumpInsn(IFLT, labelOutOfRange); // [input]

        // Check Max
        mv.visitInsn(DUP2);                   // [input, input] <-- FIX: Duplicating for consumption
        mv.visitLdcInsn(max);                 // [input, input, max]
        mv.visitInsn(DCMPG);                  // [input, res]
        mv.visitJumpInsn(IFGE, labelOutOfRange); // [input]

        // In Range
        mv.visitInsn(POP2);                   // Remove input
        ctx.visitNodeCompute(node.whenInRange());
        mv.visitJumpInsn(GOTO, labelEnd);

        // Out of Range
        mv.visitLabel(labelOutOfRange);
        mv.visitInsn(POP2);                   // Remove input
        ctx.visitNodeCompute(node.whenOutOfRange());

        mv.visitLabel(labelEnd);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.RangeChoice node, PipelineAsmContext ctx, int destArrayVar) {
        // ... (оптимизации min/max те же) ...
        double min = node.minInclusive();
        double max = node.maxExclusive();
        double inMin = node.input().minValue();
        double inMax = node.input().maxValue();

        if (inMin >= min && inMax < max) { ctx.visitNodeFill(node.whenInRange(), destArrayVar); return; }
        if (inMax < min || inMin >= max) { ctx.visitNodeFill(node.whenOutOfRange(), destArrayVar); return; }

        ctx.visitNodeFill(node.input(), destArrayVar);

        int sharedCtxVar = ctx.newLocalInt();

        ctx.arrayForI(destArrayVar, (iVar) -> {
            Label labelOutOfRange = new Label();
            Label labelEnd = new Label();

            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD); // [val]

            // Check Min
            mv.visitInsn(DUP2);
            mv.visitLdcInsn(min);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFLT, labelOutOfRange);

            // Check Max
            mv.visitInsn(DUP2); // <-- FIX
            mv.visitLdcInsn(max);
            mv.visitInsn(DCMPG);
            mv.visitJumpInsn(IFGE, labelOutOfRange);

            // In Range
            mv.visitInsn(POP2); // Remove val
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            compileBranch(mv, node.whenInRange(), ctx, iVar, sharedCtxVar);
            mv.visitInsn(DASTORE);
            mv.visitJumpInsn(GOTO, labelEnd);

            // Out of Range
            mv.visitLabel(labelOutOfRange);
            mv.visitInsn(POP2); // Remove val
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            compileBranch(mv, node.whenOutOfRange(), ctx, iVar, sharedCtxVar);
            mv.visitInsn(DASTORE);

            mv.visitLabel(labelEnd);
        });
    }

    private void compileBranch(MethodVisitor mv, DensityFunction func, PipelineAsmContext ctx, int iVar, int ctxVarSlot) {
        if (func instanceof DensityFunctions.Constant c) {
            mv.visitLdcInsn(c.value());
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
            mv.visitVarInsn(ASTORE, ctxVarSlot);

            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(ctxVarSlot);
            ctx.visitNodeCompute(func);
            ctx.setCurrentContextVar(oldCtx);
        }
    }
}