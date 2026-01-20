package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {
    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.MulOrAdd node, Step step) {

        if(step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        final var mv = ctx.mv();

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

            ctx.readNode(input, Step.Compute);
            return;
        }

        // 4. Стандартная генерация
        ctx.readNode(input, Step.Compute);

        // Генерируем константу и операцию
        mv.visitLdcInsn(arg);

        if (type == DensityFunctions.MulOrAdd.Type.MUL) {
            mv.visitInsn(DMUL);
        } else {
            mv.visitInsn(DADD);
        }
    }
}
