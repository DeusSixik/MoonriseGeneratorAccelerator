package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DADD;
import static org.objectweb.asm.Opcodes.DMUL;

public class DensityCompilerCache2DTask extends DensityCompilerTask<NoiseChunk.Cache2D> {
    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.Cache2D node, DensityCompilerContext ctx) {
        ctx.emitLeafCall(mv, node);
    }
}
