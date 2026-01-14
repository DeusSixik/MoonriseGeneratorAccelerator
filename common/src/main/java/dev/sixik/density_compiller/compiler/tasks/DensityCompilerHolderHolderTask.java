package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerHolderHolderTask extends DensityCompilerTask<DensityFunctions.HolderHolder> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.HolderHolder node, DensityCompilerContext ctx) {
        ctx.compileNodeCompute(mv, node.function().value());
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.HolderHolder node, DensityCompilerContext ctx, int destArrayVar) {
        ctx.compileNodeFill(node.function().value(), destArrayVar);
    }
}
