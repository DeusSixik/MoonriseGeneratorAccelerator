package dev.sixik.asm.handlers;

import dev.sixik.asm.VariablesManipulator;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public interface AsmCtxHandler {

    VariablesManipulator manipulator();

    MethodVisitor mv();

    int newLocalRef();

    int newLocalInt();

    int newLocalDouble();

    default void castVariableFromTo(int varFrom, int varTo) {
        final var mp = manipulator();
        final VariablesManipulator.VariableType fromType = mp.getVariableType(varFrom);
        final VariablesManipulator.VariableType toType = mp.getVariableType(varTo);

        if(!VariablesManipulator.canCast(fromType, toType))
            throw new RuntimeException("Can't cast '" + fromType.name() + "' to '" + toType.name() + "' !");

        if(VariablesManipulator.isPrimitive(toType)) {

            if(fromType == VariablesManipulator.VariableType.INT || fromType == VariablesManipulator.VariableType.ARRAY_I) {
                switch (toType) {
                    case LONG -> mv().visitInsn(I2L);
                    case FLOAT -> mv().visitInsn(I2F);
                    case DOUBLE -> mv().visitInsn(I2D);
                }
            }
            if(fromType == VariablesManipulator.VariableType.DOUBLE || fromType == VariablesManipulator.VariableType.ARRAY_D) {
                switch (toType) {
                    case LONG -> mv().visitInsn(D2L);
                    case FLOAT -> mv().visitInsn(D2F);
                    case INT -> mv().visitInsn(D2I);
                }
            }
            if(fromType == VariablesManipulator.VariableType.LONG) {
                switch (toType) {
                    case DOUBLE -> mv().visitInsn(L2D);
                    case FLOAT -> mv().visitInsn(L2F);
                    case INT -> mv().visitInsn(L2I);
                }
            }

            if(fromType == VariablesManipulator.VariableType.FLOAT) {
                switch (toType) {
                    case DOUBLE -> mv().visitInsn(F2D);
                    case LONG -> mv().visitInsn(F2L);
                    case INT -> mv().visitInsn(F2I);
                }
            }
        } else {
            throw new UnsupportedOperationException("Can't casting '" + fromType.name() + "'  to '" + toType.name() + "' !");
        }
    }

    default void pushInt(int v) {
        if (v >= -1 && v <= 5) {
            mv().visitInsn(v == -1 ? ICONST_M1 : (ICONST_0 + v));
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv().visitIntInsn(BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv().visitIntInsn(SIPUSH, v);
        } else {
            mv().visitLdcInsn(v);
        }
    }
}
