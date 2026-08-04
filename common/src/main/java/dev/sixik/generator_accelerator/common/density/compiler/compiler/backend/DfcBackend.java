package dev.sixik.generator_accelerator.common.density.compiler.compiler.backend;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.plan.CompilationPlan;

public interface DfcBackend {
    String name();

    boolean supports(CompilationPlan plan);

    DfcBackendResult compile(CompilationPlan plan);
}
