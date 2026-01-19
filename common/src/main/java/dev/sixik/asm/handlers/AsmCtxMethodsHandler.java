package dev.sixik.asm.handlers;

import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public interface AsmCtxMethodsHandler extends AsmCtxHandler {

    default void invokeMethodInterface(
            Class<?> owner,
            String methodName,
            String descriptor
    ) {
        mv().visitMethodInsn(
                INVOKEINTERFACE,
                Type.getInternalName(owner),
                methodName,
                descriptor,
                true
        );
    }

    default void invokeMethodVirtual(
            Class<?> owner,
            String methodName,
            String descriptor
    ) {
        mv().visitMethodInsn(
                INVOKEVIRTUAL,
                Type.getInternalName(owner),
                methodName,
                descriptor,
                false
        );
    }

    default void invokeMethodStatic(
            Class<?> owner,
            String methodName,
            String descriptor
    ) {
        mv().visitMethodInsn(
                INVOKESTATIC,
                Type.getInternalName(owner),
                methodName,
                descriptor,
                false
        );
    }
}

