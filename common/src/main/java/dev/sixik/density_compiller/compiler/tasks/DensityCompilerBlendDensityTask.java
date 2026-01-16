package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    private static final String BLENDER = "net/minecraft/world/level/levelgen/blending/Blender";

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLENDER_BITS);
        ctx.cache().needCachedForIndex = true;

    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        int variable = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLENDER);
        ctx.readRefVar(variable);

        ctx.loadFunctionContext();          // Function Context
        ctx.visitNodeCompute(node.input()); // input_double

        ctx.invokeMethodVirtual(
                Blender.class,
                "blendDensity",
                DescriptorBuilder.builder()
                        .type(DensityFunction.FunctionContext.class)
                        .d()
                        .buildMethod(double.class)
        );

        // blendDensity(functionContext, double)
    }
}
