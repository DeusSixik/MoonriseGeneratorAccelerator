package dev.sixik.asm.handlers;

import dev.sixik.asm.VariablesManipulator;

import static org.objectweb.asm.Opcodes.*;

public interface AsmCtxOperationsHandler extends AsmCtxHandler {

// --- Arithmetic Ops (Stack based) ---

    default void add(VariablesManipulator.VariableType type) {
        if (type == VariablesManipulator.VariableType.INT) mv().visitInsn(IADD);
        else if (type == VariablesManipulator.VariableType.LONG) mv().visitInsn(LADD);
        else if (type == VariablesManipulator.VariableType.FLOAT) mv().visitInsn(FADD);
        else if (type == VariablesManipulator.VariableType.DOUBLE) mv().visitInsn(DADD);
    }

    default void sub(VariablesManipulator.VariableType type) {
        int op = switch (type) {
            case INT -> ISUB;
            case LONG -> LSUB;
            case FLOAT -> FSUB;
            case DOUBLE -> DSUB;
            default -> throw new IllegalArgumentException("Type " + type + " doesn't support subtraction");
        };
        mv().visitInsn(op);
    }

    default void mul(VariablesManipulator.VariableType type) {
        int op = switch (type) {
            case INT -> IMUL;
            case LONG -> LMUL;
            case FLOAT -> FMUL;
            case DOUBLE -> DMUL;
            default -> throw new IllegalArgumentException("Type " + type + " doesn't support multiplication");
        };
        mv().visitInsn(op);
    }

    default void div(VariablesManipulator.VariableType type) {
        int op = switch (type) {
            case INT -> IDIV;
            case LONG -> LDIV;
            case FLOAT -> FDIV;
            case DOUBLE -> DDIV;
            default -> throw new IllegalArgumentException("Type " + type + " doesn't support division");
        };
        mv().visitInsn(op);
    }

    // --- Bitwise & Shifts (Int/Long only) ---

    default void shiftLeft(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LSHL : ISHL);
    }

    default void shiftRight(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LSHR : ISHR);
    }

    default void unsignedShiftRight(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LUSHR : IUSHR);
    }

    default void bitwiseAnd(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LAND : IAND);
    }

    default void bitwiseOr(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LOR : IOR);
    }

    default void bitwiseXor(VariablesManipulator.VariableType type) {
        mv().visitInsn(type == VariablesManipulator.VariableType.LONG ? LXOR : IXOR);
    }

    // --- Specialized Ops ---

    /**
     * Оптимизированный инкремент для локальной переменной (только int)
     */
    default void incrementIntVar(int varId, int increment) {
        manipulator().hasInt(varId);
        mv().visitIincInsn(varId, increment);
    }

    /**
     * Остаток от деления (%)
     */
    default void rem(VariablesManipulator.VariableType type) {
        int op = switch (type) {
            case INT -> IREM;
            case LONG -> LREM;
            case FLOAT -> FREM;
            case DOUBLE -> DREM;
            default -> throw new IllegalArgumentException("Type " + type + " doesn't support remainder");
        };
        mv().visitInsn(op);
    }

    /**
     * Смена знака (унарный минус)
     */
    default void neg(VariablesManipulator.VariableType type) {
        int op = switch (type) {
            case INT -> INEG;
            case LONG -> LNEG;
            case FLOAT -> FNEG;
            case DOUBLE -> DNEG;
            default -> throw new IllegalArgumentException("Type " + type + " doesn't support negation");
        };
        mv().visitInsn(op);
    }
}
