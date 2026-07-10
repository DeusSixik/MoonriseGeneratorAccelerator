package dev.sixik.generator_accelerator.common.surface_compiler.mask;

import java.util.Arrays;

public final class Mask4096 {
    public static final int WORD_COUNT = 64;

    private final long[] words = new long[WORD_COUNT];

    public long[] words() {
        return this.words;
    }

    public void fill() {
        Arrays.fill(this.words, -1L);
    }

    public void clear() {
        Arrays.fill(this.words, 0L);
    }

    public void set(int index) {
        this.words[index >>> 6] |= 1L << (index & 63);
    }

    public boolean isEmpty() {
        for (long word : this.words) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    public void loadMatchingBlockIds(int[] rawBlockData, int blockId) {
        clear();
        for (int i = 0; i < rawBlockData.length && i < 4096; i++) {
            if (rawBlockData[i] == blockId) {
                set(i);
            }
        }
    }
}
