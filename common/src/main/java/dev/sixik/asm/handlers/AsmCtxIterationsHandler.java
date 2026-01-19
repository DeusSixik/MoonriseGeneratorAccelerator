package dev.sixik.asm.handlers;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

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
        mv().goTo(labels.breakLabel());
    }

    /**
     * Переход к следующей итерации (GOTO на метку инкремента/проверки)
     */
    default void loopContinue() {
        LoopLabels labels = getLoopStack().peek();
        if (labels == null) throw new RuntimeException("Continue outside of loop!");
        mv().goTo(labels.continueLabel());
    }

    /**
     * Классический цикл while.
     * @param condition Условие продолжения (например, ctx.intLt())
     * @param body Тело цикла
     */
    default void whileLoop(AsmCtxConditionsHandler.ConditionGenerator condition, Runnable body) {
        final var ga = mv();
        Label start = ga.newLabel();
        Label end = ga.newLabel();
        getLoopStack().push(new LoopLabels(start, end));

        ga.mark(start);
        condition.generate(end);

        body.run();

        ga.goTo(start);
        ga.mark(end);

        getLoopStack().pop();
    }

    /**
     * Цикл for по диапазону int.
     * @param start Начальное значение
     * @param end Конечное значение (исключая)
     * @param body Тело цикла, принимает ID переменной-счетчика
     */
    default void forRange(int start, int end, Consumer<Integer> body) {
        final var ga = mv();
        int indexVar = ga.newLocal(Type.INT_TYPE);
        ga.push(start);
        ga.storeLocal(indexVar);

        Label startLabel = ga.newLabel();
        Label continueLabel = ga.newLabel();
        Label breakLabel = ga.newLabel();

        ga.mark(startLabel);

        ga.loadLocal(indexVar);
        ga.push(end);
        ga.ifICmp(GeneratorAdapter.GE, breakLabel);

        getLoopStack().push(new LoopLabels(continueLabel, breakLabel));
        body.accept(indexVar);
        getLoopStack().pop();

        ga.mark(continueLabel);
        ga.iinc(indexVar, 1);
        ga.goTo(startLabel);

        ga.mark(breakLabel);
    }

    default void forEachInt(int arrayVar, Consumer<Integer> elementConsumer) {
        final var ga = mv();
        int lengthVar = ga.newLocal(Type.INT_TYPE);
        int indexVar = ga.newLocal(Type.INT_TYPE);
        int elementVar = ga.newLocal(Type.INT_TYPE);

        ga.loadLocal(arrayVar);
        ga.arrayLength();
        ga.storeLocal(lengthVar);

        ga.push(0);
        ga.storeLocal(indexVar);

        Label startLabel = ga.newLabel();
        Label continueLabel = ga.newLabel();
        Label breakLabel = ga.newLabel();

        ga.mark(startLabel);

        ga.loadLocal(indexVar);
        ga.loadLocal(lengthVar);
        ga.ifICmp(GeneratorAdapter.GE, breakLabel);

        ga.loadLocal(arrayVar);
        ga.loadLocal(indexVar);
        ga.arrayLoad(Type.INT_TYPE);
        ga.storeLocal(elementVar);

        getLoopStack().push(new LoopLabels(continueLabel, breakLabel));
        elementConsumer.accept(elementVar);
        getLoopStack().pop();

        ga.mark(continueLabel);
        ga.iinc(indexVar, 1);
        ga.goTo(startLabel);

        ga.mark(breakLabel);
    }
}
