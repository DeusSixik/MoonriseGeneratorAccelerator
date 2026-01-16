package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.DensityCompiler.CTX;
import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    private static final String BLENDER = "net/minecraft/world/level/levelgen/blending/Blender";

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLENDER_BITS);
        ctx.cache().needCachedForIndex = true;
    }
    
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        int variable = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLENDER);
        ctx.readRefVar(variable);

        ctx.readFunctionContext(); // Function Context
        ctx.visitNodeCompute(node.input()); // input_double

        mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER, "blendDensity", "(L" + CTX + ";D)D", false);

        // blendDensity(functionContext, double)
    }
}
