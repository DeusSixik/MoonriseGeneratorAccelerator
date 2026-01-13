package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerBlendAlphaTask extends DensityCompilerTask<DensityFunctions.BlendAlpha> {

    @Override
    protected void compileCompute(MethodVisitor visitor, DensityFunctions.BlendAlpha function, DensityCompilerContext context) {
        visitor.visitLdcInsn(1.0);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.BlendAlpha node, DensityCompilerContext ctx, int destArrayVar) {
        ctx.arrayForFill(destArrayVar, 1.0);
    }
}
