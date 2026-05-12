package dev.sixik.generator_accelerator.common.worldgen.commit;

/**
 * Detached placeholder payload for final dirty-section/palette repack work.
 */
public record GAFinalRepackValue(
        int sectionY,
        long dirtyBlockColumnMask,
        long dirtyHeightColumnMask
) {
}
