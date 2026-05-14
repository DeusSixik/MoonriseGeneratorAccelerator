package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Value-only payload for future owner-applied postprocessing marks.
 */
public record GAPostprocessMarkValue(long sourceSequence) {
    public GAPostprocessMarkValue {
        if (sourceSequence < 0L) {
            throw new IllegalArgumentException("sourceSequence must be non-negative");
        }
    }
}
