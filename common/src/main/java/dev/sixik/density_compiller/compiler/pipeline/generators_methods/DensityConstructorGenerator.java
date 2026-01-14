package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.Map;

import static dev.sixik.density_compiller.compiler.DensityCompiler.INTERFACE_NAME;
import static org.objectweb.asm.Opcodes.*;

public class DensityConstructorGenerator implements DensityCompilerPipelineGenerator{

    @Override
    public void applyMethod(
            DensityCompilerPipeline pipeline,
            PipelineAsmContext ctx,
            DensityFunction root,
            String className,
            String classSimpleName,
            int id
    ) {
        final var mv = ctx.mv();

        ctx.loadThis();
        mv.visitMethodInsn(INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                DescriptorBuilder.builder().buildMethodVoid(),
                false);

        final Map<DensityFunction, Integer> leavesMap = pipeline.locals.leafToId;
        for (var entry : leavesMap.entrySet()) {
            final int index = entry.getValue();

            ctx.loadThis();
            ctx.aload(1);
            ctx.iconst(index);
            mv.visitInsn(AALOAD);
            ctx.putField(index);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 2);
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        final Map<DensityFunction, Integer> leavesMap = pipeline.locals.leafToId;
        final String descriptor = leavesMap.isEmpty()
                ? DescriptorBuilder.builder().buildMethodVoid()
                : DescriptorBuilder.builder().array(DensityFunction.class).buildMethodVoid();

        return cw.visitMethod(ACC_PUBLIC,
                "<init>",
                descriptor,
                null,
                null);
    }

    @Override
    public ByteCodeGeneratorStructure getStructure(DensityCompilerPipeline pipeline) {
        return new ByteCodeGeneratorStructure(2, -1);
    }

    @Override
    public void generateClassField(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final String desc = DescriptorBuilder.builder().type(DensityFunction.class).build();

        final Map<DensityFunction, Integer> leavesMap = pipeline.locals.leafToId;
        for (var index : leavesMap.values()) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL,
                    PipelineAsmContext.DEFAULT_LEAF_FUNCTION_NAME + "_" + index,
                    desc, null, null);
        }
    }
}
