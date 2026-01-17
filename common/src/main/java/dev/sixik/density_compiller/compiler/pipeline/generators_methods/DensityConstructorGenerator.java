package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.Map;

import static dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext.DEFAULT_LEAF_FUNCTION_NAME;
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

        Map<Object, Integer> leavesMap = pipeline.locals.leafToId;
        for (var entry : leavesMap.entrySet()) {
            int index = entry.getValue();
            String desc = pipeline.locals.leafTypes.getOrDefault(index, "Lnet/minecraft/world/level/levelgen/DensityFunction;");
            String internalName = Type.getType(desc).getInternalName();

            ctx.loadThis();
            ctx.aload(1); // Массив Object[] (бывший DensityFunction[])
            ctx.iconst(index);
            mv.visitInsn(AALOAD);

            // ВАЖНО: Кастим Object к конкретному типу поля (NoiseHolder, Spline...)
            mv.visitTypeInsn(CHECKCAST, internalName);

            mv.visitFieldInsn(PUTFIELD, className, DEFAULT_LEAF_FUNCTION_NAME.apply(entry.getKey()) + "_" + index, desc);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 2);
    }

    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        Map<Object, Integer> leavesMap = pipeline.locals.leafToId;
        final String descriptor = leavesMap.isEmpty()
                ? DescriptorBuilder.builder().buildMethodVoid()
                : DescriptorBuilder.builder().array(Object.class).buildMethodVoid();

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
        final Map<Object, Integer> leavesMap = pipeline.locals.leafToId;

        for (var entry : leavesMap.entrySet()) {
            int index = entry.getValue();
            // Берем дескриптор из карты типов. Если нет - по дефолту DensityFunction
            String desc = pipeline.locals.leafTypes.getOrDefault(index, "Lnet/minecraft/world/level/levelgen/DensityFunction;");

            cw.visitField(ACC_PRIVATE | ACC_FINAL,
                    DEFAULT_LEAF_FUNCTION_NAME.apply(entry.getKey()) + "_" + index,
                    desc, null, null).visitEnd();
        }
    }
}
