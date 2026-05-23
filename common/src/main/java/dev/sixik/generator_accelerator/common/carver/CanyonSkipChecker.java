package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class CanyonSkipChecker implements WorldCarver.CarveSkipChecker {
    private int minGenY;
    private float[] widthFactors;

    public void setup(int minGenY, float[] widthFactors) {
        this.minGenY = minGenY;
        this.widthFactors = widthFactors;
    }

    public int getMinGenY() {
        return this.minGenY;
    }

    public float[] getWidthFactors() {
        return this.widthFactors;
    }

    @Override
    public boolean shouldSkip(CarvingContext carvingContext, double scaledX, double scaledY, double scaledZ, int y) {
        int widthIndex = y - this.minGenY - 1;
        return (scaledX * scaledX + scaledZ * scaledZ) * (double) this.widthFactors[widthIndex] + scaledY * scaledY * 0.1666666666666667 >= 1.0D;
    }
}
