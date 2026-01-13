package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerRangeChoiceTask extends DensityCompilerTask<DensityFunctions.RangeChoice> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.RangeChoice node, DensityCompilerContext ctx) {
        // --- ШАГ 1: Вычисляем Input ---
        ctx.compileNode(mv, node.input());
        // Стек: [inputResult]

        // Нам нужно значение 2 раза для сравнений. Дублировать на стеке сложно (надо DUP2 и т.д.).
        // Проще сохранить во временную локальную переменную.
        // Переменные 0 (this) и 1 (context) заняты. Используем 2 (double занимает 2 слота, так что займет 2 и 3).
        mv.visitVarInsn(DSTORE, 2);

        // Создаем метки для прыжков
        Label labelOutOfRange = new Label();
        Label labelEnd = new Label();

        // --- ШАГ 2: Проверка MIN (input < minInclusive) ---
        mv.visitVarInsn(DLOAD, 2);           // Грузим input
        mv.visitLdcInsn(node.minInclusive()); // Грузим min
        mv.visitInsn(DCMPL);                 // Сравниваем (дает -1 если input < min)
        // Если input < min, прыгаем в OutOfRange
        mv.visitJumpInsn(IFLT, labelOutOfRange);

        // --- ШАГ 3: Проверка MAX (input >= maxExclusive) ---
        mv.visitVarInsn(DLOAD, 2);           // Грузим input
        mv.visitLdcInsn(node.maxExclusive()); // Грузим max
        mv.visitInsn(DCMPG);                 // Сравниваем (дает 1 если input > max, 0 если равны)
        // Если input >= max, прыгаем в OutOfRange
        mv.visitJumpInsn(IFGE, labelOutOfRange);

        // --- ШАГ 4: Ветка InRange (Если мы не прыгнули, значит мы в диапазоне) ---
        ctx.compileNode(mv, node.whenInRange());
        // После вычисления результата, мы должны перепрыгнуть ветку else
        mv.visitJumpInsn(GOTO, labelEnd);

        // --- ШАГ 5: Ветка OutOfRange ---
        mv.visitLabel(labelOutOfRange); // Ставим метку "сюда прыгать, если условие не прошло"
        ctx.compileNode(mv, node.whenOutOfRange());

        // --- ШАГ 6: Конец ---
        mv.visitLabel(labelEnd); // Метка конца, сюда прыгаем после InRange

        // На стеке сейчас лежит результат либо от InRange, либо от OutOfRange
    }
}
