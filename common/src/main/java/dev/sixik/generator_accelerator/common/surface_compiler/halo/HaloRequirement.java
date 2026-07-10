package dev.sixik.generator_accelerator.common.surface_compiler.halo;

public record HaloRequirement(int dx, int dy, int dz) {
    public boolean isLocal() {
        return dx == 0 && dy == 0 && dz == 0;
    }
}
