package dev.sixik.generator_accelerator.common.worldgen.transaction;

@FunctionalInterface
public interface GATransactionSandboxUnit {
    void run(GATransactionSandboxContext context) throws Exception;
}
