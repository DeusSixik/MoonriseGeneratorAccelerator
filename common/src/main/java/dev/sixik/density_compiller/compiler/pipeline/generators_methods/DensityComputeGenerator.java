package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.DRETURN;

public class DensityComputeGenerator implements DensityCompilerPipelineGenerator{

    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final MethodVisitor mv = ctx.mv();

//        ctx.preCacheConstants();

        ctx.visitNodeCompute(root);

        mv.visitInsn(DRETURN);                // Return result (double)
        mv.visitMaxs(0, 0); // ASM will calculate the stacks itself
    }

    @Override
    public ByteCodeGeneratorStructure getStructure(DensityCompilerPipeline pipeline) {
        return new ByteCodeGeneratorStructure(2, 1);
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return cw.visitMethod(
                ACC_PUBLIC,
                "compute",
                DescriptorBuilder.builder()
                        .type(DensityFunction.FunctionContext.class)
                        .buildMethod(double.class),
                null,
                null
        );
    }
}
