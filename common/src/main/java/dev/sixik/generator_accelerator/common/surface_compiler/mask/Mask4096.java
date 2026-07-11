package dev.sixik.generator_accelerator.common.surface_compiler.mask;

import java.util.Arrays;

public final class Mask4096 {
    public static final int WORD_COUNT = 64;
    public static final int BIT_COUNT = 4096;

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

    public void clear(int index) {
        this.words[index >>> 6] &= ~(1L << (index & 63));
    }

    public boolean get(int index) {
        return (this.words[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    public void copyFrom(Mask4096 other) {
        System.arraycopy(other.words, 0, this.words, 0, WORD_COUNT);
    }

    public void or(Mask4096 other) {
        long[] dst = this.words;
        long[] src = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            dst[i] |= src[i];
        }
    }

    public void and(Mask4096 other) {
        long[] dst = this.words;
        long[] src = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            dst[i] &= src[i];
        }
    }

    public void andNot(Mask4096 other) {
        long[] dst = this.words;
        long[] src = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            dst[i] &= ~src[i];
        }
    }

    public void xor(Mask4096 other) {
        long[] dst = this.words;
        long[] src = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            dst[i] ^= src[i];
        }
    }

    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0 || fromIndex >= BIT_COUNT) {
            return -1;
        }
        int wordIndex = fromIndex >>> 6;
        long word = this.words[wordIndex] & (-1L << (fromIndex & 63));
        while (true) {
            if (word != 0L) {
                return (wordIndex << 6) + Long.numberOfTrailingZeros(word);
            }
            wordIndex++;
            if (wordIndex >= WORD_COUNT) {
                return -1;
            }
            word = this.words[wordIndex];
        }
    }

    public boolean isEmpty() {
        long[] src = this.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            long word = src[i];
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }

    public void loadMatchingBlockIds(int[] rawBlockData, int blockId) {
        long[] dst = this.words;
        Arrays.fill(dst, 0L);
        int length = Math.min(rawBlockData.length, BIT_COUNT);
        for (int wordIndex = 0; wordIndex < WORD_COUNT; wordIndex++) {
            int base = wordIndex << 6;
            if (base >= length) {
                break;
            }
            long word = 0L;
            int end = Math.min(base + 64, length);
            for (int i = base; i < end; i++) {
                if (rawBlockData[i] == blockId) {
                    word |= 1L << (i & 63);
                }
            }
            dst[wordIndex] = word;
        }
    }
}
