package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Runtime adapter that replays accepted commit commands into a target world/backend.
 */
@FunctionalInterface
public interface CommitApplier<T> {
    void apply(GACommitCommand<T> command) throws Exception;
}
