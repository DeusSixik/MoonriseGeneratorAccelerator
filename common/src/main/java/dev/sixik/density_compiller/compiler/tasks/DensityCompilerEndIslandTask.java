package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import dev.sixik.density_compiller.compiler.wrappers.EndIslandHelper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.CHECKCAST;


public class DensityCompilerEndIslandTask extends DensityCompilerTask<DensityFunctions.EndIslandDensityFunction> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.EndIslandDensityFunction node, PipelineAsmContext ctx) {
//        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_X_DIV_8_BITS, DensityFunctionsCacheHandler.BLOCK_Z_DIV_8_BITS);
        ctx.cache().needCachedForIndex = true;
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.EndIslandDensityFunction node, PipelineAsmContext ctx) {
        ctx.visitLeafReference(node);

        mv.visitTypeInsn(CHECKCAST,
                Type.getInternalName(DensityFunctions.EndIslandDensityFunction.class));

        ctx.loadFunctionContext();

        ctx.invokeMethodStatic(
                EndIslandHelper.class,
                "fastCompute",
                DescriptorBuilder.builder()
                        .type(DensityFunctions.EndIslandDensityFunction.class)
                        .type(DensityFunction.FunctionContext.class)
                        .buildMethod(double.class)
        );
    }
}
