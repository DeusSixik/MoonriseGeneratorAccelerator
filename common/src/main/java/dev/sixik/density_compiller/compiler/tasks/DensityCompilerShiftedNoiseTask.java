package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.asm.VariablesManipulator;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DescriptorBuilder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static dev.sixik.density_compiller.compiler.pipeline.context.hanlders.DensityFunctionsCacheHandler.*;

public class DensityCompilerShiftedNoiseTask extends DensityCompilerTask<DensityFunctions.ShiftedNoise> {

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.shiftX().getClass());
        ctx.visitNodeCompute(node.shiftX(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.shiftY().getClass());
        ctx.visitNodeCompute(node.shiftY(), PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.shiftZ().getClass());
        ctx.visitNodeCompute(node.shiftZ(), PREPARE_COMPUTE);
        machine.popStack();

        ctx.putNeedCachedVariable(DensityFunctionsCacheHandler.BLOCK_X_BITS, DensityFunctionsCacheHandler.BLOCK_Y_BITS, DensityFunctionsCacheHandler.BLOCK_Z_BITS);

    }

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.shiftX().getClass());
        ctx.visitNodeCompute(node.shiftX(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.shiftY().getClass());
        ctx.visitNodeCompute(node.shiftY(), POST_PREPARE_COMPUTE);
        machine.popStack();

        machine.pushStack(node.getClass(), node.shiftZ().getClass());
        ctx.visitNodeCompute(node.shiftZ(), POST_PREPARE_COMPUTE);
        machine.popStack();


        machine.pushStack(node.getClass(), "compute");
        compute(mv, node, ctx);
        machine.popStack();

        int index = ctx.createDoubleVarFromStack();
        ctx.putCachedVariable(String.valueOf(node.hashCode()), index);

    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx) {

        int variable = ctx.getCachedVariable(String.valueOf(node.hashCode()));

        final int context = ctx.getCurrentContextVar();

        if(context == -1)
            throw new NullPointerException("Can't load context because variable not loaded! Index: '" + context + "'");

        if(context == 2) {
            ctx.aload(2);
            ctx.readIntVarUnSafe(ctx.cache().fillIndex);
            ctx.invokeMethodStatic(
                    DensityCompilerShiftedNoiseTask.class,
                    "updateIndices",
                    DescriptorBuilder.builder()
                            .type(DensityFunction.ContextProvider.class)
                            .i()
                            .buildMethodVoid()

            );
        }

        ctx.readDoubleVar(variable);
    }

    private static void compute(MethodVisitor mv, DensityFunctions.ShiftedNoise node, PipelineAsmContext ctx) {
        DensityFunction.NoiseHolder holder = node.noise();
        ctx.visitCustomLeaf(holder, Type.getDescriptor(DensityFunction.NoiseHolder.class));

        var machine = ctx.pipeline().stackMachine();

        int blockX = ctx.getCachedVariable(BLOCK_X);
        int blockY = ctx.getCachedVariable(BLOCK_Y);
        int blockZ = ctx.getCachedVariable(BLOCK_Z);

        double xzScale = node.xzScale();
        double yScale = node.yScale();

        ctx.readIntVar(blockX);
        ctx.mv().visitInsn(I2D);
        ctx.mv().visitLdcInsn(xzScale);
        ctx.mul(VariablesManipulator.VariableType.DOUBLE);

        machine.pushStack("compute", node.shiftX().getClass());
        ctx.visitNodeCompute(node.shiftX());
        machine.popStack();

        ctx.add(VariablesManipulator.VariableType.DOUBLE);

        int d = ctx.createDoubleVarFromStack();

        ctx.readIntVar(blockY);
        ctx.mv().visitInsn(I2D);
        ctx.mv().visitLdcInsn(yScale);
        ctx.mul(VariablesManipulator.VariableType.DOUBLE);

        machine.pushStack("compute", node.shiftY().getClass());
        ctx.visitNodeCompute(node.shiftY());
        machine.popStack();

        ctx.add(VariablesManipulator.VariableType.DOUBLE);

        int e = ctx.createDoubleVarFromStack();


        ctx.readIntVar(blockZ);
        ctx.mv().visitInsn(I2D);
        ctx.mv().visitLdcInsn(xzScale);
        ctx.mul(VariablesManipulator.VariableType.DOUBLE);

        machine.pushStack("compute", node.shiftZ().getClass());
        ctx.visitNodeCompute(node.shiftZ());
        machine.popStack();

        ctx.add(VariablesManipulator.VariableType.DOUBLE);

        int f = ctx.createDoubleVarFromStack();

        ctx.readDoubleVar(d);
        ctx.readDoubleVar(e);
        ctx.readDoubleVar(f);

        ctx.invokeMethodVirtual(
                DensityFunction.NoiseHolder.class,
                "getValue",
                DescriptorBuilder.builder()
                        .d()
                        .d()
                        .d()
                        .buildMethod(double.class)
        );
    }

    public static void updateIndices(DensityFunction.ContextProvider provider, int index) {

        if(!(provider instanceof NoiseChunk chunk)) return;

        // 1. Получаем размеры (обычно они закэшированы в полях, доступ быстрый)
        int width = chunk.cellWidth;
        int height = chunk.cellHeight;

        // 2. Вычисляем константы для битовых операций
        // Integer.numberOfTrailingZeros компилируется в одну инструкцию CPU (TZCNT/BSF), это очень быстро.
        // Если width = 4 -> shift = 2. Если width = 8 -> shift = 3.
        int shift = Integer.numberOfTrailingZeros(width);
        int mask = width - 1;

        // 3. Обновляем индекс массива (как просил)
        chunk.arrayIndex = index;

        // 4. Считаем координаты Z и X (самые быстрые, меняются часто)
        // Z = index % width
        chunk.inCellZ = index & mask;

        // X = (index / width) % width
        chunk.inCellX = (index >> shift) & mask;

        // 5. Считаем Y (меняется медленно и инвертирован)
        // Y = (height - 1) - (index / (width * width))
        // shift << 1 — это умножение shift на 2 (для площади width * width)
        chunk.inCellY = (height - 1) - (index >> (shift << 1));
    }
}
