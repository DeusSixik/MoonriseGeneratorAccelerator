package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerFlatCacheTask extends DensityCompilerTask<NoiseChunk.FlatCache> {


    @Override
    protected void compileCompute(MethodVisitor mv, NoiseChunk.FlatCache node, PipelineAsmContext ctx) {
        ctx.visitLeafCall(node);
    }
}
