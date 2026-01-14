package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerMarkerTask extends DensityCompilerTask<DensityFunctions.Marker> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Marker node, DensityCompilerContext ctx) {
        switch (node.type()) {
            case Interpolated, FlatCache, Cache2D, CacheOnce, CacheAllInCell ->
                    ctx.emitLeafCall(mv, node);
            default -> ctx.compileNodeCompute(mv, node.wrapped());
        }
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Marker node, DensityCompilerContext ctx, int destArrayVar) {
        switch (node.type()) {
            case Interpolated, FlatCache, Cache2D, CacheOnce, CacheAllInCell ->
                    ctx.emitLeafFill(mv, node, destArrayVar);
            default -> ctx.compileNodeFill(node.wrapped(), destArrayVar);
        }
    }
}
