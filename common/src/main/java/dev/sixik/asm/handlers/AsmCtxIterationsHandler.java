package dev.sixik.asm.handlers;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.util.Deque;
import java.util.function.Consumer;

public interface AsmCtxIterationsHandler extends AsmCtxHandler, Opcodes {

    record LoopLabels(Label continueLabel, Label breakLabel) {}

    /**
     * Возвращает стек меток циклов. Должен быть реализован в PipelineAsmContext.
     */
    Deque<LoopLabels> getLoopStack();

    /**
     * Выход из текущего цикла (GOTO на метку после тела цикла)
     */
    default void loopBreak() {
        LoopLabels labels = getLoopStack().peek();
        if (labels == null) throw new RuntimeException("Break outside of loop!");
        mv().visitJumpInsn(GOTO, labels.breakLabel());
    }

    /**
     * Переход к следующей итерации (GOTO на метку инкремента/проверки)
     */
    default void loopContinue() {
        LoopLabels labels = getLoopStack().peek();
        if (labels == null) throw new RuntimeException("Continue outside of loop!");
        mv().visitJumpInsn(GOTO, labels.continueLabel());
    }

    /**
     * Классический цикл while.
     * @param condition Условие продолжения (например, ctx.intLt())
     * @param body Тело цикла
     */
    default void whileLoop(AsmCtxConditionsHandler.ConditionGenerator condition, Runnable body) {
        Label start = new Label();
        Label end = new Label();

        mv().visitLabel(start);
        condition.generate(end); // Прыжок в конец, если условие ложно

        body.run();

        mv().visitJumpInsn(GOTO, start);
        mv().visitLabel(end);
    }

    /**
     * Цикл for по диапазону int.
     * @param start Начальное значение
     * @param end Конечное значение (исключая)
     * @param body Тело цикла, принимает ID переменной-счетчика
     */
    default void forRange(int start, int end, Consumer<Integer> body) {
        int indexVar = newLocalInt();
        manipulator().registerInteger(indexVar);
        pushInt(start);
        mv().visitVarInsn(ISTORE, indexVar);

        Label startLabel = new Label();   // Проверка условия
        Label continueLabel = new Label(); // Инкремент
        Label breakLabel = new Label();    // Выход

        mv().visitLabel(startLabel);
        mv().visitVarInsn(ILOAD, indexVar);
        pushInt(end);
        mv().visitJumpInsn(IF_ICMPGE, breakLabel);

        // Регистрация цикла в стеке
        getLoopStack().push(new LoopLabels(continueLabel, breakLabel));
        body.accept(indexVar);
        getLoopStack().pop();

        // Точка continue
        mv().visitLabel(continueLabel);
        mv().visitIincInsn(indexVar, 1);
        mv().visitJumpInsn(GOTO, startLabel);

        mv().visitLabel(breakLabel);
    }

    default void forEachInt(int arrayVar, Consumer<Integer> elementConsumer) {
        int lengthVar = newLocalInt();
        manipulator().registerInteger(lengthVar);
        int indexVar = newLocalInt();
        manipulator().registerInteger(indexVar);
        int elementVar = newLocalInt();
        manipulator().registerInteger(elementVar);

        mv().visitVarInsn(ALOAD, arrayVar);
        mv().visitInsn(ARRAYLENGTH);
        mv().visitVarInsn(ISTORE, lengthVar);
        pushInt(0);
        mv().visitVarInsn(ISTORE, indexVar);

        Label startLabel = new Label();
        Label continueLabel = new Label();
        Label breakLabel = new Label();

        mv().visitLabel(startLabel);
        mv().visitVarInsn(ILOAD, indexVar);
        mv().visitVarInsn(ILOAD, lengthVar);
        mv().visitJumpInsn(IF_ICMPGE, breakLabel);

        mv().visitVarInsn(ALOAD, arrayVar);
        mv().visitVarInsn(ILOAD, indexVar);
        mv().visitInsn(IALOAD);
        mv().visitVarInsn(ISTORE, elementVar);

        getLoopStack().push(new LoopLabels(continueLabel, breakLabel));
        elementConsumer.accept(elementVar);
        getLoopStack().pop();

        mv().visitLabel(continueLabel);
        mv().visitIincInsn(indexVar, 1);
        mv().visitJumpInsn(GOTO, startLabel);

        mv().visitLabel(breakLabel);
    }
}
