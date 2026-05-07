package dev.sixik.generator_accelerator.common.surface.compiler.mask;

import java.util.Arrays;
import java.util.BitSet;

public final class Mask4096 {
    public static final int BIT_COUNT = 4096;
    public static final int WORD_COUNT = BIT_COUNT >>> 6;
    private static final int WORD_BIT_COUNT = Long.SIZE;
    private static final long[] ALL_COLUMNS = {-1L, -1L, -1L, -1L};

    private final long[] words = new long[WORD_COUNT];

    public long[] words() {
        return this.words;
    }

    public void clear() {
        Arrays.fill(this.words, 0L);
    }

    public void fill() {
        Arrays.fill(this.words, -1L);
    }

    public void set(int bit) {
        this.words[bit >>> 6] |= 1L << bit;
    }

    public void clear(int bit) {
        this.words[bit >>> 6] &= ~(1L << bit);
    }

    public boolean get(int bit) {
        return (this.words[bit >>> 6] & (1L << bit)) != 0L;
    }

    public void copyFrom(Mask4096 other) {
        System.arraycopy(other.words, 0, this.words, 0, WORD_COUNT);
    }

    public void and(Mask4096 other) {
        long[] a = this.words;
        long[] b = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            a[i] &= b[i];
        }
    }

    public void or(Mask4096 other) {
        long[] a = this.words;
        long[] b = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            a[i] |= b[i];
        }
    }

    public void xor(Mask4096 other) {
        long[] a = this.words;
        long[] b = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            a[i] ^= b[i];
        }
    }

    public void andNot(Mask4096 other) {
        long[] a = this.words;
        long[] b = other.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            a[i] &= ~b[i];
        }
    }

    public void notWithinStone(Mask4096 stoneMask) {
        long[] a = this.words;
        long[] s = stoneMask.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            a[i] = ~a[i] & s[i];
        }
    }

    public boolean isEmpty() {
        long[] a = this.words;
        for (int i = 0; i < WORD_COUNT; i++) {
            if (a[i] != 0L) {
                return false;
            }
        }
        return true;
    }

    public void fillColumnYRange(int x, int z, int minLocalYInclusive, int maxLocalYInclusive) {
        if (minLocalYInclusive < 0) minLocalYInclusive = 0;
        if (maxLocalYInclusive > 15) maxLocalYInclusive = 15;
        if (minLocalYInclusive > maxLocalYInclusive) return;

        int xz = (z << 4) | x;
        for (int y = minLocalYInclusive; y <= maxLocalYInclusive; y++) {
            set((y << 8) | xz);
        }
    }

    public void loadMatchingBlockIds(int[] rawBlockData, int blockId) {
        long[] a = this.words;
        for (int wordIndex = 0; wordIndex < WORD_COUNT; wordIndex++) {
            int base = wordIndex << 6;
            long word = 0L;
            for (int bit = 0; bit < 64; bit++) {
                if (rawBlockData[base + bit] == blockId) {
                    word |= 1L << bit;
                }
            }
            a[wordIndex] = word;
        }
    }

    public boolean hasColumnBits(int xz) {
        int wordBase = xz >>> 6;
        long bit = 1L << (xz & 63);
        long[] a = this.words;
        for (int y = 0; y < 16; y++) {
            if ((a[(y << 2) + wordBase] & bit) != 0L) {
                return true;
            }
        }
        return false;
    }

    public void computeActiveColumns(long[] targetWords4) {
        long[] a = this.words;
        targetWords4[0] = 0L;
        targetWords4[1] = 0L;
        targetWords4[2] = 0L;
        targetWords4[3] = 0L;

        for (int y = 0; y < 16; y++) {
            int base = y << 2;
            targetWords4[0] |= a[base];
            targetWords4[1] |= a[base + 1];
            targetWords4[2] |= a[base + 2];
            targetWords4[3] |= a[base + 3];
        }
    }

    public void clearColumn(int xz) {
        int wordBase = xz >>> 6;
        long mask = ~(1L << (xz & 63));
        long[] a = this.words;
        for (int y = 0; y < 16; y++) {
            a[(y << 2) + wordBase] &= mask;
        }
    }

    public void clearColumnBelow(int xz, int minLocalYToKeep) {
        if (minLocalYToKeep <= 0) {
            return;
        }
        if (minLocalYToKeep >= 16) {
            clearColumn(xz);
            return;
        }

        int wordBase = xz >>> 6;
        long mask = ~(1L << (xz & 63));
        long[] a = this.words;
        for (int y = 0; y < minLocalYToKeep; y++) {
            a[(y << 2) + wordBase] &= mask;
        }
    }

    public void orColumnFrom(Mask4096 source, int xz) {
        int wordBase = xz >>> 6;
        long bit = 1L << (xz & 63);
        long[] dst = this.words;
        long[] src = source.words;
        for (int y = 0; y < 16; y++) {
            int wordIndex = (y << 2) + wordBase;
            dst[wordIndex] |= src[wordIndex] & bit;
        }
    }

    public void applyBlockState(int[] rawBlockData, int stateId) {
        long[] a = this.words;
        for (int wordIndex = 0; wordIndex < WORD_COUNT; wordIndex++) {
            long word = a[wordIndex];
            if (word == -1L) {
                int base = wordIndex << 6;
                Arrays.fill(rawBlockData, base, base + WORD_BIT_COUNT, stateId);
                continue;
            }
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                rawBlockData[(wordIndex << 6) + bit] = stateId;
                word &= word - 1L;
            }
        }
    }

    public void applyBlockStateAndClearAbove(int[] rawBlockData, int stateId, int minLocalYInclusive) {
        applyBlockStateAndClearAbove(rawBlockData, stateId, minLocalYInclusive, ALL_COLUMNS, null);
    }

    public void applyBlockStateAndClearAbove(
            int[] rawBlockData,
            int stateId,
            int minLocalYInclusive,
            long[] allowedColumns,
            long[] activeColumnsScratch
    ) {
        if (minLocalYInclusive <= 0) {
            applyBlockStateAndClearColumns(rawBlockData, stateId, allowedColumns, activeColumnsScratch);
            return;
        }
        if (minLocalYInclusive >= 16) {
            return;
        }

        if (activeColumnsScratch != null) {
            computeActiveColumns(activeColumnsScratch);
        }
        long[] a = this.words;
        for (int y = minLocalYInclusive; y < 16; y++) {
            int yBase = y << 2;
            int blockBase = y << 8;
            for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                long allowed = allowedColumns[columnWordIndex];
                if (allowed == 0L) {
                    continue;
                }
                int wordIndex = yBase + columnWordIndex;
                long word = a[wordIndex] & allowed;
                if (activeColumnsScratch != null) {
                    word &= activeColumnsScratch[columnWordIndex];
                }
                if (word == -1L) {
                    int base = blockBase + (columnWordIndex << 6);
                    Arrays.fill(rawBlockData, base, base + WORD_BIT_COUNT, stateId);
                    a[wordIndex] &= ~allowed;
                    continue;
                }
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    rawBlockData[blockBase + (columnWordIndex << 6) + bit] = stateId;
                    word &= word - 1L;
                }
                a[wordIndex] &= ~allowed;
            }
        }
    }

    public void applyBlockStateAndClearColumns(int[] rawBlockData, int stateId, long[] allowedColumns, long[] activeColumnsScratch) {
        if (activeColumnsScratch != null) {
            computeActiveColumns(activeColumnsScratch);
        }
        long[] a = this.words;
        for (int y = 0; y < 16; y++) {
            int yBase = y << 2;
            int blockBase = y << 8;
            for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                long allowed = allowedColumns[columnWordIndex];
                if (allowed == 0L) {
                    continue;
                }
                int wordIndex = yBase + columnWordIndex;
                long word = a[wordIndex] & allowed;
                if (activeColumnsScratch != null) {
                    word &= activeColumnsScratch[columnWordIndex];
                }
                if (word == -1L) {
                    int base = blockBase + (columnWordIndex << 6);
                    Arrays.fill(rawBlockData, base, base + WORD_BIT_COUNT, stateId);
                    a[wordIndex] &= ~allowed;
                    continue;
                }
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    rawBlockData[blockBase + (columnWordIndex << 6) + bit] = stateId;
                    word &= word - 1L;
                }
                a[wordIndex] &= ~allowed;
            }
        }
    }

    public void applyBlockStateAndClearAbove(int[] rawBlockData, int stateId, int[] minLocalYByColumn, long[] activeColumnsScratch) {
        applyBlockStateAndClearAbove(rawBlockData, stateId, minLocalYByColumn, activeColumnsScratch, activeColumnsScratch);
    }

    public void applyBlockStateAndClearAbove(
            int[] rawBlockData,
            int stateId,
            int[] minLocalYByColumn,
            long[] allowedColumns,
            long[] activeColumnsScratch
    ) {
        if (activeColumnsScratch != null) {
            computeActiveColumns(activeColumnsScratch);
        }
        long[] a = this.words;
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = activeColumnsScratch == null
                    ? allowedColumns[columnWordIndex]
                    : activeColumnsScratch[columnWordIndex] & allowedColumns[columnWordIndex];
            while (columnWord != 0L) {
                int columnBit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + columnBit;
                int minY = minLocalYByColumn[xz];
                if (minY < 0) {
                    minY = 0;
                }
                if (minY < 16) {
                    long bitMask = 1L << columnBit;
                    for (int y = minY; y < 16; y++) {
                        int wordIndex = (y << 2) + columnWordIndex;
                        if ((a[wordIndex] & bitMask) != 0L) {
                            rawBlockData[(y << 8) | xz] = stateId;
                            a[wordIndex] &= ~bitMask;
                        }
                    }
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    public void applyBlockStateAndClearLayered(int[] rawBlockData, int stateId, long[] layerColumnMasks) {
        long[] a = this.words;
        for (int wordIndex = 0; wordIndex < WORD_COUNT; wordIndex++) {
            long word = a[wordIndex] & layerColumnMasks[wordIndex];
            if (word == 0L) {
                continue;
            }
            if (word == -1L) {
                int base = wordIndex << 6;
                Arrays.fill(rawBlockData, base, base + WORD_BIT_COUNT, stateId);
                a[wordIndex] = 0L;
                continue;
            }
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                rawBlockData[(wordIndex << 6) + bit] = stateId;
                word &= word - 1L;
            }
            a[wordIndex] &= ~layerColumnMasks[wordIndex];
        }
    }

    public void toBitSet(BitSet target) {
        target.clear();
        long[] a = this.words;
        for (int wordIndex = 0; wordIndex < WORD_COUNT; wordIndex++) {
            long word = a[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                target.set((wordIndex << 6) + bit);
                word &= word - 1L;
            }
        }
    }

    public void fromBitSet(BitSet source) {
        clear();
        for (int i = source.nextSetBit(0); i >= 0 && i < BIT_COUNT; i = source.nextSetBit(i + 1)) {
            set(i);
        }
    }
}
