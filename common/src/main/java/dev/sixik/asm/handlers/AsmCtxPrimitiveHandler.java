package dev.sixik.asm.handlers;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.ISTORE;

public interface AsmCtxPrimitiveHandler extends AsmCtxHandler {

    /**
     * Резервирует слот для переменной типа Double
     * @return ID переменной
     */
    default int allocDoubleVar() {
        int variable = newLocalDouble();
        manipulator().registerDouble(variable);
        return variable;
    }

    /**
     * Позволяет положить в переменную значение через ASM
     * @param varId ID переменной
     */
    default void writeDoubleVar(int varId) {
        manipulator().hasDouble(varId);
        mv().visitVarInsn(DSTORE, varId);
    }

    /**
     * Позволяет положить в переменную конкретное значение
     * @param varId ID переменной
     */
    default void writeDoubleVar(int varId, double value) {
        mv().visitLdcInsn(value);
        writeDoubleVar(varId);
    }

    /**
     * Создаёт переменную типа Double с каким-то значением
     * @param value Значение переменной
     * @return ID переменной
     */
    default int createDoubleVar(double value) {
        int variable = allocDoubleVar();
        writeDoubleVar(variable, value);
        return variable;
    }

    /**
     * Создаёт переменную типа Double
     * @return ID переменной
     */
    default int createDoubleVar() {
        int variable = allocDoubleVar();
        mv().visitInsn(DCONST_0);
        writeDoubleVar(variable);
        return variable;
    }

    /**
     * Создаёт переменную типа Double
     * @return ID переменной
     */
    default int createDoubleVarFromStack() {
        int variable = allocDoubleVar();
        writeDoubleVar(variable);
        return variable;
    }

    /**
     * Читает данные из переменной типа Double
     * @param varId ID переменной
     */
    default void readDoubleVar(int varId) {
        manipulator().hasDouble(varId);
        mv().visitVarInsn(DLOAD, varId);
    }

    /**
     * Резервирует слот для переменной типа Integer
     * @return ID переменной
     */
    default int allocIntVar() {
        int variable = newLocalInt();
        manipulator().registerInteger(variable);
        return variable;
    }

    /**
     * Позволяет положить в переменную значение через ASM
     * @param varId ID переменной
     */
    default void writeIntVar(int varId) {
        manipulator().hasInt(varId);
        mv().visitVarInsn(ISTORE, varId);
    }

    /**
     * Позволяет положить в переменную конкретное значение
     * @param varId ID переменной
     */
    default void writeIntVar(int varId, int value) {
        mv().visitLdcInsn(value);
        writeIntVar(varId);
    }

    /**
     * Создаёт переменную типа Integer с каким-то значением
     * @param value Значение переменной
     * @return ID переменной
     */
    default int createIntVar(int value) {
        int variable = allocIntVar();
        writeIntVar(variable, value);
        return variable;
    }

    /**
     * Создаёт переменную типа Integer
     * @return ID переменной
     */
    default int createIntVar() {
        int variable = allocIntVar();
        mv().visitInsn(ICONST_0);
        writeIntVar(variable);
        return variable;
    }

    /**
     * Создаёт переменную типа Integer для записи данные из Stack
     * @return ID переменной
     */
    default int createIntVarFromStack() {
        int variable = allocIntVar();
        writeIntVar(variable);
        return variable;
    }

    /**
     * Читает данные из переменной типа Integer
     * @param varId ID переменной
     */
    default void readIntVar(int varId) {
        manipulator().hasInt(varId);
        readIntVarUnSafe(varId);
    }

    /**
     * Читает данные из переменной типа Integer
     * @param varId ID переменной
     */
    default void readIntVarUnSafe(int varId) {
        mv().visitVarInsn(ILOAD, varId);
    }

    /**
     * Резервирует слот для переменной типа Object
     * @return ID переменной
     */
    default int allocRefVar() {
        int variable = newLocalRef();
//        manipulator().registerReference(variable);
        return variable;
    }

    /**
     * Позволяет положить в переменную значение через ASM
     * @param varId ID переменной
     */
    default void writeRefVar(int varId) {
//        manipulator().hasReference(varId);
        mv().visitVarInsn(ASTORE, varId);
    }

    /**
     * Позволяет положить в переменную конкретное значение
     * @param varId ID переменной
     */
    default void writeRefVar(int varId, Object value) {
        mv().visitLdcInsn(value);
        writeRefVar(varId);
    }

    /**
     * Создаёт переменную типа Object с каким-то значением
     * @param value Значение переменной
     * @return ID переменной
     */
    default int createRefVar(Object value) {
        int variable = allocRefVar();
        writeRefVar(variable, value);
        return variable;
    }

    /**
     * Создаёт переменную типа Object
     * @return ID переменной
     */
    default int createRefVar() {
        int variable = allocRefVar();
        mv().visitInsn(ACONST_NULL);
        writeRefVar(variable);
        return variable;
    }

    /**
     * Создаёт переменную типа Object для записи данные из Stack
     * @return ID переменной
     */
    default int createRefVarFromStack() {
        int variable = allocRefVar();
        writeRefVar(variable);
        return variable;
    }

    /**
     * Читает данные из переменной типа Object
     * @param varId ID переменной
     */
    default void readRefVar(int varId) {
//        manipulator().hasReference(varId);
        mv().visitVarInsn(ALOAD, varId);
    }


    /**
     * Резервирует слот для переменной типа Long
     * @return ID переменной
     */
    default int allocLongVar() {
        int variable = newLocalDouble();
        manipulator().registerLong(variable);
        return variable;
    }

    /**
     * Позволяет положить в переменную значение из стека через ASM
     * @param varId ID переменной
     */
    default void writeLongVar(int varId) {
        manipulator().hasLong(varId);
        mv().visitVarInsn(LSTORE, varId);
    }

    /**
     * Позволяет положить в переменную конкретное значение long
     * @param varId ID переменной
     */
    default void writeLongVar(int varId, long value) {
        if (value == 0L) {
            mv().visitInsn(LCONST_0);
        } else if (value == 1L) {
            mv().visitInsn(LCONST_1);
        } else {
            mv().visitLdcInsn(value);
        }
        writeLongVar(varId);
    }

    /**
     * Создаёт переменную типа Long с заданным значением
     * @param value Значение переменной
     * @return ID переменной
     */
    default int createLongVar(long value) {
        int variable = allocLongVar();
        writeLongVar(variable, value);
        return variable;
    }

    /**
     * Создаёт переменную типа Long со значением 0
     * @return ID переменной
     */
    default int createLongVar() {
        int variable = allocLongVar();
        mv().visitInsn(LCONST_0);
        writeLongVar(variable);
        return variable;
    }

    /**
     * Создаёт переменную типа Long для записи данных из Stack
     * @return ID переменной
     */
    default int createLongVarFromStack() {
        int variable = allocLongVar();
        writeLongVar(variable);
        return variable;
    }

    /**
     * Читает данные из переменной типа Long на стек
     * @param varId ID переменной
     */
    default void readLongVar(int varId) {
        manipulator().hasLong(varId);
        mv().visitVarInsn(LLOAD, varId);
    }
}
