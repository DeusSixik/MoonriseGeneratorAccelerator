package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerConstantTask extends DensityCompilerTask<DensityFunctions.Constant> {

    @Override
    protected void compileCompute(MethodVisitor visitor, DensityFunctions.Constant node, PipelineAsmContext ctx) {
        ctx.mv().visitLdcInsn(node.value());
    }
}
