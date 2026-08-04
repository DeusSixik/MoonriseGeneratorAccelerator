package dev.sixik.generator_accelerator.common.surface_compiler.ir;

public record SurfaceStateToken(int ordinal) {
    public static SurfaceStateToken initial() {
        return new SurfaceStateToken(0);
    }

    public SurfaceStateToken next() {
        return new SurfaceStateToken(this.ordinal + 1);
    }
}
