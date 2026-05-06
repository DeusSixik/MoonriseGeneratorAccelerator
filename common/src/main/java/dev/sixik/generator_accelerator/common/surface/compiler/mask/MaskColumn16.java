package dev.sixik.generator_accelerator.common.surface.compiler.mask;

import java.util.Arrays;

public final class MaskColumn16 {
    private final long[] words = new long[4];

    public void clear() {
        Arrays.fill(this.words, 0L);
    }

    public void set(int xz) {
        this.words[xz >>> 6] |= 1L << xz;
    }

    public boolean get(int xz) {
        return (this.words[xz >>> 6] & (1L << xz)) != 0L;
    }
}
