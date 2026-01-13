package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerClampTask extends DensityCompilerTask<DensityFunctions.Clamp> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Clamp node, DensityCompilerContext ctx) {
        ctx.compileNode(mv, node.input());

        mv.visitLdcInsn(node.minValue());
        mv.visitLdcInsn(node.maxValue());

        DensityCompilerMath.clamp(mv);
    }
}
