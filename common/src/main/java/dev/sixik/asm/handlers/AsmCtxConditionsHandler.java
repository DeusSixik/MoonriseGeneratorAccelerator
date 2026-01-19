package dev.sixik.asm.handlers;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import static org.objectweb.asm.Opcodes.*;

public interface AsmCtxConditionsHandler extends AsmCtxHandler {

    /**
     * Конструктор if-else. Использует ga.mark() для корректных фреймов.
     */
    default void ifElse(ConditionGenerator jumpCondition, Runnable thenBlock, @Nullable Runnable elseBlock) {
        final var ga = mv();
        Label elseLabel = ga.newLabel();
        Label endLabel = ga.newLabel();

        // Генерируем условие: если ложь -> прыжок в else
        jumpCondition.generate(elseLabel);

        thenBlock.run();
        ga.goTo(endLabel);

        ga.mark(elseLabel);
        if (elseBlock != null) {
            elseBlock.run();
        }
        ga.mark(endLabel);
    }

    // --- Integer Comparisons ---

    default ConditionGenerator intEq() {
        return label -> mv().ifICmp(GeneratorAdapter.NE, label);
    }

    default ConditionGenerator intLt() {
        return label -> mv().ifICmp(GeneratorAdapter.GE, label);
    }

    // --- Double / Long / Float Comparisons ---
    // GeneratorAdapter.ifCmp умеет сам работать с Long, Double, Float и Objects!

    default ConditionGenerator doubleLt() {
        // ifCmp сам выберет DCMPG/DCMPL и нужный IF-прыжок
        return label -> mv().ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.GE, label);
    }

    default ConditionGenerator doubleEq() {
        return label -> mv().ifCmp(Type.DOUBLE_TYPE, GeneratorAdapter.NE, label);
    }

    default ConditionGenerator longLt() {
        return label -> mv().ifCmp(Type.LONG_TYPE, GeneratorAdapter.GE, label);
    }

    // --- Reference Comparisons ---

    default ConditionGenerator refEq() {
        return label -> mv().ifCmp(Type.getType(Object.class), GeneratorAdapter.NE, label);
    }

    default ConditionGenerator refNull() {
        return label -> mv().ifNonNull(label);
    }

    @FunctionalInterface
    interface ConditionGenerator {
        /**
         * Генерирует код, который прыгнет к метке, если условие НЕ ВЫПОЛНЕНО.
         */
        void generate(Label jumpIfFalse);
    }
}
