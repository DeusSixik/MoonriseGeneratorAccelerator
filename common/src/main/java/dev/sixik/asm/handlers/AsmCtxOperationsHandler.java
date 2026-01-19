package dev.sixik.asm.handlers;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

public interface AsmCtxOperationsHandler extends AsmCtxHandler {

    default void add(Type type) {
        mv().math(GeneratorAdapter.ADD, type);
    }

    default void sub(Type type) {
        mv().math(GeneratorAdapter.SUB, type);
    }

    default void mul(Type type) {
        mv().math(GeneratorAdapter.MUL, type);
    }

    default void div(Type type) {
        mv().math(GeneratorAdapter.DIV, type);
    }

    default void rem(Type type) {
        mv().math(GeneratorAdapter.REM, type);
    }

    default void neg(Type type) {
        mv().math(GeneratorAdapter.NEG, type);
    }

    default void shiftLeft(Type type) {
        mv().math(GeneratorAdapter.SHL, type);
    }

    default void shiftRight(Type type) {
        mv().math(GeneratorAdapter.SHR, type);
    }

    default void unsignedShiftRight(Type type) {
        mv().math(GeneratorAdapter.USHR, type);
    }

    default void bitwiseAnd(Type type) {
        mv().math(GeneratorAdapter.AND, type);
    }

    default void bitwiseOr(Type type) {
        mv().math(GeneratorAdapter.OR, type);
    }

    default void bitwiseXor(Type type) {
        mv().math(GeneratorAdapter.XOR, type);
    }

    default void incrementIntVar(int varId, int increment) {
        mv().iinc(varId, increment);
    }
}