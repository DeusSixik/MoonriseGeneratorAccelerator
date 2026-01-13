package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, DensityCompilerContext ctx) {

        /*
            blockY (int) -> double
         */
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "blockY", "()I", true);
        mv.visitInsn(I2D);

        /*
            fromY (int) -> double
         */
        mv.visitLdcInsn(node.fromY());
        mv.visitInsn(I2D);

        /*
            toY (int) -> double
         */
        mv.visitLdcInsn(node.toY());
        mv.visitInsn(I2D);

        /*
            romValue and toValue already double
         */
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        DensityCompilerMath.clampedMap(mv); // Stack [double, double, double, double, double] 10 slots
    }
}
