package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerHolderHolderTask extends DensityCompilerTask<DensityFunctions.HolderHolder> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.HolderHolder node, DensityCompilerContext ctx) {
        ctx.compileNode(mv, node.function().value());
    }
}
