package dev.sixik.density_compiller.compiler.pipeline;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityConstructorGenerator implements DensityCompilerPipelineGenerator{
    @Override
    public void apply(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final var mv = ctx.mv();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                DescriptorBuilder.builder().buildMethodVoid(),
                false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD,
                pipeline.configurator.className(),
                "leaves",
                "[L" + pipeline.configurator.interfaces_names()[0] + ";");

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return cw.visitMethod(ACC_PUBLIC,
                "<init>",
                DescriptorBuilder.builder()
                        .array(DensityFunction.class)
                        .buildMethodVoid(),
                null,
                null);
    }

    @Override
    public ByteCodeGeneratorStructure getStructure() {
        return new ByteCodeGeneratorStructure(0, 0);
    }
}
