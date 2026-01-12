package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerBlendOffsetTask extends DensityCompilerTask<DensityFunctions.BlendOffset> {

    @Override
    protected void compileCompute(MethodVisitor visitor, DensityFunctions.BlendOffset function, DensityCompilerContext context) {
        visitor.visitLdcInsn(0.0);
    }
}
