package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Detached placeholder payload for postprocessing marks committed by chunk owner.
 */
public record GAPostprocessMarkValue(long sourceSequence) {
    public GAPostprocessMarkValue {
        if (sourceSequence < 0L) {
            throw new IllegalArgumentException("sourceSequence must be non-negative");
        }
    }
}
