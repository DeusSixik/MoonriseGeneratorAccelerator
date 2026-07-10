package dev.sixik.generator_accelerator.common.surface_compiler.telemetry;

public final class AllocationSampler {
    private long samples;
    private long bytes;

    public void record(long bytes) {
        this.samples++;
        this.bytes += Math.max(0L, bytes);
    }

    public long samples() {
        return this.samples;
    }

    public long bytes() {
        return this.bytes;
    }

    public void reset() {
        this.samples = 0L;
        this.bytes = 0L;
    }
}
