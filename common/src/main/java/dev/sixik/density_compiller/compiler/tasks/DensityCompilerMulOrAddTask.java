package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.MulOrAdd function, DensityCompilerContext ctx) {
        ctx.compileNode(function.argument1());

    }
}
