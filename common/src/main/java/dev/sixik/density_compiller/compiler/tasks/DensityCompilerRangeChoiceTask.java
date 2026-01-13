package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, DensityCompilerContext ctx) {
        ctx.compileNodeCompute(mv, node.input());

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

        ctx.compileNodeCompute(mv, node.whenInRange()); // Stack: [res_in]
        mv.visitJumpInsn(GOTO, labelEnd);

        mv.visitLabel(labelPopAndOut);
        mv.visitInsn(POP2);

        mv.visitLabel(labelOut);
        ctx.compileNodeCompute(mv, node.whenOutOfRange()); // Stack: [res_out]

        mv.visitLabel(labelEnd);
    }
}
