package dev.sixik.density_compiler.tasks;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    private static final Type MATH_TYPE = Type.getType(Math.class);
    private static final Type MTH_TYPE = Type.getType(Mth.class);

    @Override
    protected void applyStep(DCAsmContext ctx, DensityFunctions.Mapped node, Step step) {
        ctx.readNode(node.input(), step);

        if(step != Step.Compute) return;

        if (node.type() == DensityFunctions.Mapped.Type.ABS && node.input().minValue() >= 0.0) {
            return;
        }

        if ((node.type() == DensityFunctions.Mapped.Type.HALF_NEGATIVE || node.type() == DensityFunctions.Mapped.Type.QUARTER_NEGATIVE)
                && node.input().minValue() >= 0.0) {
            return;
        }

        GeneratorAdapter ga = ctx.mv();
        generateTransformMath(ga, node.type());
    }

    private void generateTransformMath(GeneratorAdapter ga, DensityFunctions.Mapped.Type type) {
        // На входе: Stack: [..., double_input]
        // На выходе: Stack: [..., double_result]

        switch (type) {
            case ABS -> {
                // Math.abs(d)
                ga.invokeStatic(MATH_TYPE, Method.getMethod("double abs(double)"));
            }
            case SQUARE -> {
                // d * d
                ga.dup2(); // Stack: [d, d]
                ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);
            }
            case CUBE -> {
                // d * d * d
                ga.dup2(); // Stack: [d, d]
                ga.dup2(); // Stack: [d, d, d]
                ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE); // Stack: [d, d*d]
                ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE); // Stack: [d*d*d]
            }
            case HALF_NEGATIVE -> {
                generateConditionalScale(ga, 0.5);
            }
            case QUARTER_NEGATIVE -> {
                generateConditionalScale(ga, 0.25);
            }
            case SQUEEZE -> {
                generateSqueeze(ga);
            }
            default -> throw new IllegalStateException("Unknown Mapped Type: " + type);
        }
    }


    /**
     * Генерирует логику: d > 0 ? d : d * scale
     */
    private void generateConditionalScale(GeneratorAdapter ga, double scale) {
        Label end = ga.newLabel();
        Label multiply = ga.newLabel();

        // Stack: [d]
        ga.dup2();      // Stack: [d, d]
        ga.push(0.0);   // Stack: [d, d, 0.0]

        // Сравниваем d с 0.0.
        // Если d <= 0, идем умножать. (LE = Less or Equal)
        // Иначе (d > 0) пропускаем.
        ga.ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.LE, multiply);

        // --- Путь d > 0 ---
        // Stack: [d] (ничего не делаем, возвращаем d)
        ga.goTo(end);

        // --- Путь d <= 0 ---
        ga.mark(multiply);
        // Stack: [d]
        ga.push(scale); // Stack: [d, scale]
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE);

        ga.mark(end);
    }

    /**
     * Реализует формулу SQUEEZE:
     * e = Mth.clamp(d, -1.0, 1.0);
     * return e / 2.0 - (e * e * e) / 24.0;
     */
    private void generateSqueeze(GeneratorAdapter ga) {
        // Stack: [d]

        // 1. e = Mth.clamp(d, -1.0, 1.0)
        ga.push(-1.0);
        ga.push(1.0);
        ga.invokeStatic(MTH_TYPE, Method.getMethod("double clamp(double, double, double)"));
        // Stack: [e]

        // Чтобы не сходить с ума с DUP-ами для сложной формулы,
        // проще сохранить 'e' во временную локальную переменную.
        // GeneratorAdapter эффективно переиспользует слоты.
        int eVar = ga.newLocal(Type.DOUBLE_TYPE);
        ga.storeLocal(eVar);

        // Формула: (e / 2.0) - ((e * e * e) / 24.0)

        // Часть 1: e / 2.0
        ga.loadLocal(eVar);
        ga.push(2.0);
        ga.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);
        // Stack: [term1]

        // Часть 2: (e * e * e) / 24.0
        ga.loadLocal(eVar);
        ga.loadLocal(eVar);
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE); // e^2
        ga.loadLocal(eVar);
        ga.math(GeneratorAdapter.MUL, Type.DOUBLE_TYPE); // e^3
        ga.push(24.0);
        ga.math(GeneratorAdapter.DIV, Type.DOUBLE_TYPE);
        // Stack: [term1, term2]

        // Финал: term1 - term2
        ga.math(GeneratorAdapter.SUB, Type.DOUBLE_TYPE);
    }
}
