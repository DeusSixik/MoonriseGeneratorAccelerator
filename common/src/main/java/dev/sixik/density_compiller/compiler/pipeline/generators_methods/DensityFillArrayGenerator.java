package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityFillArrayGenerator implements DensityCompilerPipelineGenerator {
    @Override
    public void applyMethod(DensityCompilerPipeline pipeline, PipelineAsmContext ctx, DensityFunction root, String className, String classSimpleName, int id) {
        final MethodVisitor mv = ctx.mv();
        int destArrayVar = 1;

        // 1. Кэшируем Blender ЗА ПРЕДЕЛАМИ цикла
        // Вызываем provider.forIndex(0).getBlender() один раз на весь метод
        ctx.preCacheConstants();

        // 2. Открываем ОДИН цикл
        ctx.startLoop();
        ctx.arrayForI(destArrayVar, (iVar) -> {
            // Стек для DASTORE: [Array, Index]
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            // 3. Получаем контекст для итерации
            int loopCtx = ctx.getOrAllocateLoopContext(iVar);
            int oldCtx = ctx.getCurrentContextVar();
            ctx.setCurrentContextVar(loopCtx);

            // 4. Инлайним всю математику дерева в этот цикл
            ctx.visitNodeCompute(root);

            ctx.setCurrentContextVar(oldCtx);

            // 5. Записываем результат: [Array, Index, Result] -> DASTORE
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
}
