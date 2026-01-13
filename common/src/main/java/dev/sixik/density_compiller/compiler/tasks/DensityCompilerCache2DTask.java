package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerCache2DTask extends DensityCompilerTask<NoiseChunk.Cache2D> {
    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.Cache2D node, DensityCompilerContext ctx) {
        ctx.emitLeafCall(mv, node);
    }
}
