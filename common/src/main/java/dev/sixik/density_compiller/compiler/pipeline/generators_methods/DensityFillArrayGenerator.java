package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityFillArrayGenerator implements DensityCompilerPipelineGenerator {

    @Override
    public void prepareMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        ctx.visitNodeCompute(root, DensityCompilerTask.PREPARE_COMPUTE);
    }

    @Override
    public void postPrepareMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        ctx.visitNodeCompute(root, DensityCompilerTask.POST_PREPARE_COMPUTE);
    }

    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final MethodVisitor mv = ctx.mv();
        int destArrayVar = 1;

        // 1. Кэшируем Blender ЗА ПРЕДЕЛАМИ цикла
        // Вызываем provider.forIndex(0).getBlender() один раз на весь метод
//        ctx.preCacheConstants();

        // 2. Открываем ОДИН цикл
//        ctx.startLoop();

        ctx.arrayForI(destArrayVar, (iVar) -> {

            // Стек для DASTORE: [Array, Index]
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            if(ctx.cache().needCachedForIndex) {

                ctx.aload(2);
                ctx.readIntVarUnSafe(iVar);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider", "forIndex", "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;", true);
                ctx.cache().cachedForIndexVar = ctx.createRefVarFromStack();
            }

            ctx.visitNodeCompute(root);

            mv.visitInsn(DASTORE);
        });

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
    }


    @Override
    public MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return cw.visitMethod(
                ACC_PUBLIC,
                "fillArray",
                DescriptorBuilder.builder()
                        .array(double.class)
                        .type(DensityFunction.ContextProvider.class)
                        .buildMethodVoid(),
                null,
                null);
    }

    @Override
    public ByteCodeGeneratorStructure getStructure(DensityCompilerPipeline pipeline) {
        return new ByteCodeGeneratorStructure(5, 2);
    }
}
