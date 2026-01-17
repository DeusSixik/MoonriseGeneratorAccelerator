package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();

        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLENDER_BITS);
        ctx.cache().needCachedForIndex = true;

    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        ctx.comment("Owner: DensityCompilerBlendDensityTask");

        var machine = ctx.pipeline().stackMachine();
        int blenderVar = ctx.getCachedVariable(DensityFunctionsCacheHandler.BLENDER);

        // --- FAST PATH CHECK ---
        // Проверяем, не является ли блендер пустым.
        // Это оптимизация ветвления: JIT очень хорошо предсказывает этот переход,
        // так как в пределах одного чанка блендер либо всегда пустой, либо всегда нет.

        Label doBlend = new Label();
        Label end = new Label();

        // 1. Грузим Blender
        ctx.readRefVar(blenderVar);

        // 2. Грузим Blender.empty() (константу)
        ctx.invokeMethodStatic(
                Blender.class,
                "empty",
                DescriptorBuilder.builder().buildMethod(Blender.class)
        );

        // 3. Сравниваем ссылки (IF_ACMPNE - if references are NOT equal)
        mv.visitJumpInsn(IF_ACMPNE, doBlend);

        // --- CASE: EMPTY BLENDER ---
        // Просто считаем input и выходим. Никаких invoke context.
        ctx.visitNodeCompute(node.input());
        mv.visitJumpInsn(GOTO, end);

        // --- CASE: ACTIVE BLENDER ---
        mv.visitLabel(doBlend);

        // Грузим Blender снова (или можно было сделать DUP перед сравнением, но так чище для стека)
        ctx.readRefVar(blenderVar);
        ctx.loadFunctionContext();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input());
        machine.popStack();

        ctx.invokeMethodVirtual(
                Blender.class,
                "blendDensity",
                DescriptorBuilder.builder()
                        .type(DensityFunction.FunctionContext.class)
                        .d()
                        .buildMethod(double.class)
        );

        mv.visitLabel(end);
    }
}
