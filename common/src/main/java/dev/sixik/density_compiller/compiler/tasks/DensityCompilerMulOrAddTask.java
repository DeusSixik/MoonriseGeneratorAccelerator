package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {

    @Override
    protected void postPrepareCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), POST_PREPARE_COMPUTE);
        machine.popStack();

    }

    @Override
    protected void prepareCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();

        machine.pushStack(node.getClass(), node.input().getClass());
        ctx.visitNodeCompute(node.input(), PREPARE_COMPUTE);
        machine.popStack();
    }

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, PipelineAsmContext ctx) {
        var machine = ctx.pipeline().stackMachine();
        ctx.comment("MulOrAdd: " + node.specificType().name() + " " + node.argument());

        double arg = node.argument();
        var type = node.specificType();
        DensityFunction input = node.input();

        // 1. Оптимизация: Constant Folding (Если вход тоже константа)
        // Visitor это должен делать, но проверка здесь дешевая и надежная.
        if (input instanceof DensityFunctions.Constant c) {
            double val = c.value();
            double result = (type == DensityFunctions.MulOrAdd.Type.MUL) ? val * arg : val + arg;
            mv.visitLdcInsn(result);
            return;
        }

        // 2. Оптимизация: Умножение на 0
        if (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 0.0) {
            mv.visitInsn(DCONST_0);
            return;
        }

        // 3. Оптимизация: Identity (+0 или *1)
        // Просто делегируем генерацию инпуту, не добавляя математику
        if ((type == DensityFunctions.MulOrAdd.Type.ADD && arg == 0.0) ||
                (type == DensityFunctions.MulOrAdd.Type.MUL && arg == 1.0)) {

            machine.pushStack(node.getClass(), input.getClass());
            ctx.visitNodeCompute(input);
            machine.popStack();
            return;
        }

        // 4. Стандартная генерация
        machine.pushStack(node.getClass(), input.getClass());
        ctx.visitNodeCompute(input);
        machine.popStack();

        // Генерируем константу и операцию
        mv.visitLdcInsn(arg);

        if (type == DensityFunctions.MulOrAdd.Type.MUL) {
            mv.visitInsn(DMUL);
        } else {
            mv.visitInsn(DADD);
        }
    }
}
