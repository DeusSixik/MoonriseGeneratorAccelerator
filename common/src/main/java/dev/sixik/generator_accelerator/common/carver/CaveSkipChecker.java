package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class CaveSkipChecker implements WorldCarver.CarveSkipChecker {
    private double floorLevel;

    public void setup(double floorLevel) {
        this.floorLevel = floorLevel;
    }

    public double getFloorLevel() {
        return this.floorLevel;
    }

    @Override
    public boolean shouldSkip(CarvingContext carvingContext, double scaledX, double scaledY, double scaledZ, int y) {
        return scaledY <= this.floorLevel || scaledX * scaledX + scaledY * scaledY + scaledZ * scaledZ >= 1.0D;
    }
}
