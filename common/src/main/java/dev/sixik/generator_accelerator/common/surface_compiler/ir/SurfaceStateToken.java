package dev.sixik.generator_accelerator.common.surface_compiler.ir;

public record SurfaceStateToken(int ordinal) {
    public SurfaceStateToken {
        if (ordinal < 0) {
            throw new IllegalArgumentException("state token ordinal must be non-negative");
        }
    }

    public static SurfaceStateToken initial() {
        return new SurfaceStateToken(0);
    }

    public SurfaceStateToken next() {
        return new SurfaceStateToken(this.ordinal + 1);
    }
}
