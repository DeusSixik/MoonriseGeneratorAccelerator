package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerFlatCacheTask extends DensityCompilerTask<NoiseChunk.FlatCache> {


    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.FlatCache node, DensityCompilerContext ctx) {
        ctx.emitLeafCall(mv, node);
    }
}
