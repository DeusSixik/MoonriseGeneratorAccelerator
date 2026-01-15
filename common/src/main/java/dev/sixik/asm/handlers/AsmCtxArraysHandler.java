package dev.sixik.asm.handlers;

import static org.objectweb.asm.Opcodes.*;

public interface AsmCtxArraysHandler extends AsmCtxHandler {

    default int allocIntArray() {
        int variable = newLocalRef();
        manipulator().registerIntArray(variable);
        return variable;
    }

    default int createIntArray(int size) {
        int variable = allocIntArray();
        pushInt(size);
        mv().visitIntInsn(NEWARRAY, T_INT);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default int createIntArrayFromStack() {
        int variable = allocIntArray();
        mv().visitIntInsn(NEWARRAY, T_INT);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default void readIntArray(int varId, Runnable genIndex) {
        manipulator().hasIntArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        mv().visitInsn(IALOAD);
    }

    default void readIntArrayPop(int varId, Runnable genIndex) {
        manipulator().hasIntArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        mv().visitInsn(IALOAD);
        mv().visitInsn(POP);
    }

    default void writeIntArray(int varId, Runnable genIndex, Runnable genValue) {
        manipulator().hasIntArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        genValue.run();
        mv().visitInsn(IASTORE);
    }

    // --- Double Array (D) ---

    default int allocDoubleArray() {
        int variable = newLocalRef();
        manipulator().registerDoubleArray(variable);
        return variable;
    }

    default int createDoubleArray(int size) {
        int variable = allocDoubleArray();
        pushInt(size);
        mv().visitIntInsn(NEWARRAY, T_DOUBLE);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default int createDoubleArrayFromStack() {
        int variable = allocDoubleArray();
        mv().visitIntInsn(NEWARRAY, T_DOUBLE);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default void readDoubleArray(int varId, Runnable genIndex) {
        manipulator().hasDoubleArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        mv().visitInsn(DALOAD);
    }

    default void writeDoubleArray(int varId, Runnable genIndex, Runnable genValue) {
        manipulator().hasDoubleArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        genValue.run();
        mv().visitInsn(DASTORE);
    }

    // --- Reference Array (A) ---

    default int allocRefArray() {
        int variable = newLocalRef();
        manipulator().registerArray(variable);
        return variable;
    }

    default int createRefArray(int size, String internalName) {
        int variable = allocRefArray();
        pushInt(size);
        mv().visitTypeInsn(ANEWARRAY, internalName);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default int createRefArrayFromStack(String internalName) {
        int variable = allocRefArray();
        mv().visitTypeInsn(ANEWARRAY, internalName);
        mv().visitVarInsn(ASTORE, variable);
        return variable;
    }

    default void readRefArray(int varId, Runnable genIndex) {
        manipulator().hasArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        mv().visitInsn(AALOAD);
    }

    default void writeRefArray(int varId, Runnable genIndex, Runnable genValue) {
        manipulator().hasArray(varId);
        mv().visitVarInsn(ALOAD, varId);
        genIndex.run();
        genValue.run();
        mv().visitInsn(AASTORE);
    }

    // --- Common ---

    default void arrayLength(int varId) {
        mv().visitVarInsn(ALOAD, varId);
        mv().visitInsn(ARRAYLENGTH);
    }
}
