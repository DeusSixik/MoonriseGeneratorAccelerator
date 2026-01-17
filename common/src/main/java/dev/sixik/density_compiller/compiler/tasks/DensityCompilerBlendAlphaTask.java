package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerBlendAlphaTask extends DensityCompilerTask<DensityFunctions.BlendAlpha> {

    @Override
    protected void compileCompute(MethodVisitor visitor, DensityFunctions.BlendAlpha function, PipelineAsmContext ctx) {
        ctx.ldc(1.0);
    }
}
