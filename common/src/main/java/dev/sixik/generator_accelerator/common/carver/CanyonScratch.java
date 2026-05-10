package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class CanyonScratch {
    private static final int MAX_RETAINED_WIDTH_FACTORS = 512;
    private static final int MAX_EXCESSIVE_WIDTH_FACTORS = 2_048;
    public final LegacyRandomSource random = new LegacyRandomSource(0L);
    public final CanyonSkipChecker skipChecker = new CanyonSkipChecker();
    private float[] widthFactors = new float[0];

    public float[] ensureWidthFactors(int requiredLength) {
        if (this.widthFactors.length > MAX_EXCESSIVE_WIDTH_FACTORS && requiredLength <= MAX_RETAINED_WIDTH_FACTORS) {
            this.widthFactors = new float[MAX_RETAINED_WIDTH_FACTORS];
        }
        if (this.widthFactors.length < requiredLength) {
            this.widthFactors = new float[requiredLength];
        }
        return this.widthFactors;
    }
}
