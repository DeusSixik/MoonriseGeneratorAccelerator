package dev.sixik.generator_accelerator.common.surface.exceptions;

public class UnknownSurfaceRuleSource extends RuntimeException {

    public UnknownSurfaceRuleSource(String message) {
        super(message);
    }

    public UnknownSurfaceRuleSource(String message, Throwable cause) {
        super(message, cause);
    }
}
