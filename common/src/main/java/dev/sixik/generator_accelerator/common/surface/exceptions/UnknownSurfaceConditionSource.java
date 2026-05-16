package dev.sixik.generator_accelerator.common.surface.exceptions;

public class UnknownSurfaceConditionSource extends RuntimeException {

    public UnknownSurfaceConditionSource(String message) {
        super(message);
    }

    public UnknownSurfaceConditionSource(String message, Throwable cause) {
        super(message, cause);
    }
}
