package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Ap2 node, DensityCompilerContext ctx) {
        ctx.compileNodeCompute(mv, node.argument1()); // Put double (+2 slots)
        ctx.compileNodeCompute(mv, node.argument2()); // Put double (+2 slots)

        switch (node.type()) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> DensityCompilerUtils.min(mv);
            case MAX -> DensityCompilerUtils.max(mv);
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Ap2 node, DensityCompilerContext ctx, int destArrayVar) {

        /*
            Optimization: Constant Folding (if both arguments are constants)
         */
        if (node.argument1() instanceof DensityFunctions.Constant c1 &&
                node.argument2() instanceof DensityFunctions.Constant c2) {

            double result = switch (node.type()) {
                case ADD -> c1.value() + c2.value();
                case MUL -> c1.value() * c2.value();
                case MIN -> Math.min(c1.value(), c2.value());
                case MAX -> Math.max(c1.value(), c2.value());
            };

           /*
                Just fill the array with the result and exit
            */
            ctx.compileNodeFill(new DensityFunctions.Constant(result), destArrayVar);
            return;
        }

        /*
            Standard logic for ADD
         */
        if (node.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {

            /*
                Filling the main array with the first argument
             */
            ctx.compileNodeFill(node.argument1(), destArrayVar);

            /*
                If the second argument is also a constant, we don't create an array!
             */
            if (node.argument2() instanceof DensityFunctions.Constant c) {
                double val = c.value();
                ctx.arrayForI(destArrayVar, (iVar) -> {
                    mv.visitVarInsn(ALOAD, destArrayVar);
                    mv.visitVarInsn(ILOAD, iVar);
                    mv.visitInsn(DUP2); // Duplicating Array + Index
                    mv.visitInsn(DALOAD);
                    mv.visitLdcInsn(val);
                    mv.visitInsn(DADD);
                    mv.visitInsn(DASTORE);
                });
                return;
            }

            /*
                If both are complex, then we only allocate a temporary buffer.
             */
            int tempArrayVar = ctx.allocateTempBuffer();
            ctx.compileNodeFill(node.argument2(), tempArrayVar);

            ctx.arrayForI(destArrayVar, (iVar) -> {
                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);

                mv.visitVarInsn(ALOAD, destArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DALOAD);

                mv.visitVarInsn(ALOAD, tempArrayVar);
                mv.visitVarInsn(ILOAD, iVar);
                mv.visitInsn(DALOAD);

                mv.visitInsn(DADD);
                mv.visitInsn(DASTORE);
            });
        }
    }
}
