package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Commit-lane payload for final dirty-section/palette repack work.
 */
public record GAFinalRepackValue(
        int sectionY,
        long dirtyBlockColumnMask,
        long dirtyHeightColumnMask
) {
}
