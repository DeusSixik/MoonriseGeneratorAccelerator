package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Documented collision behavior for commands targeting the same block.
 */
public enum GACommitCollisionPolicy {
    FIRST_WRITE_WINS,
    LATER_WRITE_WINS,
    REJECT
}
