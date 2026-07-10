package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

public interface SurfaceCommitController {
    void commit();

    void discard();

    boolean committed();
}
