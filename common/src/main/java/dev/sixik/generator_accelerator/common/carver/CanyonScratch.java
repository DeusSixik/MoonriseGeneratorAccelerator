package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

public final class CanyonScratch {
    public final LegacyRandomSource random = new LegacyRandomSource(0L);
    public final CanyonSkipChecker skipChecker = new CanyonSkipChecker();
    private float[] widthFactors = new float[0];

    public float[] ensureWidthFactors(int requiredLength) {
        if (this.widthFactors.length < requiredLength) {
            this.widthFactors = new float[requiredLength];
        }
        return this.widthFactors;
    }
}
