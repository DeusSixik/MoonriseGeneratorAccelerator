package dev.sixik.generator_accelerator.api.exceptions;

public class MethodNotImplementedException extends RuntimeException {

    public MethodNotImplementedException(Class<?> cls, String method) {
        super("[Generator Accelerator]: " + cls.getName() + " not implemented method " + method);
    }
}
