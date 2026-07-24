package dev.sixik.generator_accelerator.common.density.compiler.compiler.backend;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.cache.GlobalCompileCache;

public record DfcBackendResult(
        GlobalCompileCache.CopiedClassBundle bundle,
        boolean reusedClassFromCache) {
}
