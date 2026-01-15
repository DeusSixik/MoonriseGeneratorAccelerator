package dev.sixik.asm.handlers;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

public interface AsmCtxConditionsHandler extends AsmCtxHandler, Opcodes {

    // --- High-level If-Else Structure ---

    /**
     * Базовый конструктор if-else.
     *
     * @param jumpCondition Генерация условия прыжка (должен прыгать к метке, если условие ЛОЖНО)
     */
    default void ifElse(ConditionGenerator jumpCondition, Runnable thenBlock, @Nullable Runnable elseBlock) {
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Если условие ложно — прыгаем в else
        jumpCondition.generate(elseLabel);

        thenBlock.run();
        mv().visitJumpInsn(GOTO, endLabel);

        mv().visitLabel(elseLabel);
        if (elseBlock != null) {
            elseBlock.run();
        }
        mv().visitLabel(endLabel);
    }

    default void ifThen(ConditionGenerator jumpCondition, Runnable thenBlock) {
        ifElse(jumpCondition, thenBlock, null);
    }

    // --- Integer Comparisons (Stack: v1, v2) ---

    default ConditionGenerator intEq() {
        return label -> mv().visitJumpInsn(IF_ICMPNE, label);
    }

    default ConditionGenerator intNe() {
        return label -> mv().visitJumpInsn(IF_ICMPEQ, label);
    }

    default ConditionGenerator intLt() {
        return label -> mv().visitJumpInsn(IF_ICMPGE, label);
    }

    default ConditionGenerator intGt() {
        return label -> mv().visitJumpInsn(IF_ICMPLE, label);
    }

    default ConditionGenerator intLe() {
        return label -> mv().visitJumpInsn(IF_ICMPGT, label);
    }

    default ConditionGenerator intGe() {
        return label -> mv().visitJumpInsn(IF_ICMPLT, label);
    }

    // --- Double Comparisons (Stack: v1, v2) ---

    default ConditionGenerator doubleEq() {
        return label -> {
            mv().visitInsn(DCMPL);
            mv().visitJumpInsn(IFNE, label); // Прыжок, если v1 != v2
        };
    }

    default ConditionGenerator doubleNe() {
        return label -> {
            mv().visitInsn(DCMPL);
            mv().visitJumpInsn(IFEQ, label); // Прыжок, если v1 == v2
        };
    }

    default ConditionGenerator doubleLt() {
        return label -> {
            mv().visitInsn(DCMPG);            // NaN -> 1
            mv().visitJumpInsn(IFGE, label);  // Прыжок, если v1 >= v2 (или NaN)
        };
    }

    default ConditionGenerator doubleGt() {
        return label -> {
            mv().visitInsn(DCMPL);            // NaN -> -1
            mv().visitJumpInsn(IFLE, label);  // Прыжок, если v1 <= v2 (или NaN)
        };
    }

    default ConditionGenerator doubleLe() {
        return label -> {
            mv().visitInsn(DCMPG);            // NaN -> 1
            mv().visitJumpInsn(IFGT, label);  // Прыжок, если v1 > v2 (или NaN)
        };
    }

    default ConditionGenerator doubleGe() {
        return label -> {
            mv().visitInsn(DCMPL);            // NaN -> -1
            mv().visitJumpInsn(IFLT, label);  // Прыжок, если v1 < v2 (или NaN)
        };
    }

    // --- Long Comparisons (Stack: v1, v2) ---

    default ConditionGenerator longEq() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFNE, label); // Прыжок, если v1 != v2
        };
    }

    default ConditionGenerator longNe() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFEQ, label); // Прыжок, если v1 == v2
        };
    }

    default ConditionGenerator longLt() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFGE, label); // Прыжок, если v1 >= v2
        };
    }

    default ConditionGenerator longGt() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFLE, label); // Прыжок, если v1 <= v2
        };
    }

    default ConditionGenerator longLe() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFGT, label); // Прыжок, если v1 > v2
        };
    }

    default ConditionGenerator longGe() {
        return label -> {
            mv().visitInsn(LCMP);
            mv().visitJumpInsn(IFLT, label); // Прыжок, если v1 < v2
        };
    }

    // --- Reference Comparisons (Stack: ref1, ref2) ---

    default ConditionGenerator refEq() {
        return label -> mv().visitJumpInsn(IF_ACMPNE, label);
    }

    default ConditionGenerator refNe() {
        return label -> mv().visitJumpInsn(IF_ACMPEQ, label);
    }

    default ConditionGenerator refNull() {
        return label -> mv().visitJumpInsn(IFNONNULL, label);
    }

    default ConditionGenerator refNotNull() {
        return label -> mv().visitJumpInsn(IFNULL, label);
    }

    @FunctionalInterface
    interface ConditionGenerator {
        void generate(Label jumpIfFalse);
    }
}
