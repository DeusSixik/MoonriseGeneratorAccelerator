package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import dev.sixik.density_compiler.utils.DensityCompilerUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Mapped node, Step step) {

        if (step != Step.Compute) {
            ctx.readNode(node.input(), step);
            return;
        }

        final int cachedId = ctx.getVariable(node);
        if (cachedId != -1) {
            ctx.mv().loadLocal(cachedId);
            return;
        }

        final DensityFunction input = node.input();
        final DensityFunctions.Mapped.Type type = node.type();

        // 2. Быстрые оптимизации (No-op)
        // Если ABS или NEGATIVE над положительным числом — просто пробрасываем вход
        if ((type == DensityFunctions.Mapped.Type.ABS ||
                type == DensityFunctions.Mapped.Type.HALF_NEGATIVE ||
                type == DensityFunctions.Mapped.Type.QUARTER_NEGATIVE)
                && input.minValue() >= 0.0) {
            ctx.readNode(input, Step.Compute);
            return;
        }

        // 3. Вычисление входа
        ctx.readNode(input, Step.Compute);

        // 4. Генерация математики
        generateTransformMath(ctx.mv(), type, input, ctx);

        // 5. Сохранение результата в переменную
        int id = ctx.mv().newLocal(Type.DOUBLE_TYPE);
        ctx.mv().dup2();
        ctx.mv().storeLocal(id);
        ctx.setVariable(node, id);
    }

    private void generateTransformMath(MethodVisitor mv, DensityFunctions.Mapped.Type type, DensityFunction input, DCAsmContext ctx) {
        switch (type) {
            case ABS -> {
                DensityCompilerUtils.abs(mv);
            }
            case SQUARE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
            }
            case CUBE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
                mv.visitInsn(DMUL);
            }
            case HALF_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.5, input);
            case QUARTER_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.25, input);
            case SQUEEZE -> {
                boolean needsClamp = input.minValue() < -1.0 || input.maxValue() > 1.0;
                DensityCompilerUtils.compileSqueeze(mv, ctx, needsClamp);
            }
        }
    }
}
