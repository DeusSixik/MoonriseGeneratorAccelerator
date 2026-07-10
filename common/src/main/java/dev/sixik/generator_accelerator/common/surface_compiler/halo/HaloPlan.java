package dev.sixik.generator_accelerator.common.surface_compiler.halo;

public record HaloPlan(int radiusX, int radiusY, int radiusZ, boolean nonBlockingOnly, boolean downgradeIfMissing) {
    public static HaloPlan none() {
        return new HaloPlan(0, 0, 0, true, true);
    }

    public static HaloPlan required(int radiusX, int radiusY, int radiusZ) {
        return new HaloPlan(Math.max(0, radiusX), Math.max(0, radiusY), Math.max(0, radiusZ), true, true);
    }

    public boolean required() {
        return this.radiusX > 0 || this.radiusY > 0 || this.radiusZ > 0;
    }
}
