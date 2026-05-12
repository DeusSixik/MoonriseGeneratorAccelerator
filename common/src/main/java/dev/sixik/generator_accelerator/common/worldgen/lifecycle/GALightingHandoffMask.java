package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

/**
 * Minimal light engine handoff: dirty owner-local columns, not a light rewrite.
 */
public final class GALightingHandoffMask {
    private static final GALightingHandoffMask EMPTY = new GALightingHandoffMask(0L, 0L, 0L, 0L);

    private final long word0;
    private final long word1;
    private final long word2;
    private final long word3;

    private GALightingHandoffMask(long word0, long word1, long word2, long word3) {
        this.word0 = word0;
        this.word1 = word1;
        this.word2 = word2;
        this.word3 = word3;
    }

    public static GALightingHandoffMask empty() {
        return EMPTY;
    }

    public static GALightingHandoffMask fromDirtyColumns(Collection<GAColumnPosition> columns) {
        if (columns == null || columns.isEmpty()) {
            return empty();
        }
        long word0 = 0L;
        long word1 = 0L;
        long word2 = 0L;
        long word3 = 0L;
        for (GAColumnPosition column : columns) {
            if (column == null) {
                throw new NullPointerException("column");
            }
            int index = column.packedIndex();
            long bit = 1L << (index & 63);
            switch (index >>> 6) {
                case 0 -> word0 |= bit;
                case 1 -> word1 |= bit;
                case 2 -> word2 |= bit;
                case 3 -> word3 |= bit;
                default -> throw new IllegalArgumentException("column index outside chunk mask: " + index);
            }
        }
        return (word0 | word1 | word2 | word3) == 0L ? empty() : new GALightingHandoffMask(word0, word1, word2, word3);
    }

    public static GALightingHandoffMask fromDirtyColumnBits(BitSet dirtyColumns) {
        if (dirtyColumns == null || dirtyColumns.isEmpty()) {
            return empty();
        }
        if (dirtyColumns.length() > 256) {
            throw new IllegalArgumentException("dirtyColumns may only address 16x16 chunk columns");
        }
        long word0 = 0L;
        long word1 = 0L;
        long word2 = 0L;
        long word3 = 0L;
        for (int bitIndex = dirtyColumns.nextSetBit(0); bitIndex >= 0; bitIndex = dirtyColumns.nextSetBit(bitIndex + 1)) {
            long bit = 1L << (bitIndex & 63);
            switch (bitIndex >>> 6) {
                case 0 -> word0 |= bit;
                case 1 -> word1 |= bit;
                case 2 -> word2 |= bit;
                case 3 -> word3 |= bit;
                default -> throw new IllegalArgumentException("dirtyColumns may only address 16x16 chunk columns");
            }
        }
        return new GALightingHandoffMask(word0, word1, word2, word3);
    }

    public boolean isEmpty() {
        return (word0 | word1 | word2 | word3) == 0L;
    }

    public int dirtyColumnCount() {
        return Long.bitCount(word0) + Long.bitCount(word1) + Long.bitCount(word2) + Long.bitCount(word3);
    }

    public boolean contains(GAColumnPosition column) {
        if (column == null) {
            throw new NullPointerException("column");
        }
        int index = column.packedIndex();
        return (word(index >>> 6) & (1L << (index & 63))) != 0L;
    }

    public List<GAColumnPosition> dirtyColumns() {
        int count = dirtyColumnCount();
        if (count == 0) {
            return List.of();
        }
        ArrayList<GAColumnPosition> out = new ArrayList<>(count);
        appendDirtyColumns(out, word0, 0);
        appendDirtyColumns(out, word1, 64);
        appendDirtyColumns(out, word2, 128);
        appendDirtyColumns(out, word3, 192);
        return List.copyOf(out);
    }

    public BitSet toBitSet() {
        BitSet bits = new BitSet(256);
        setBits(bits, word0, 0);
        setBits(bits, word1, 64);
        setBits(bits, word2, 128);
        setBits(bits, word3, 192);
        return bits;
    }

    private long word(int index) {
        return switch (index) {
            case 0 -> word0;
            case 1 -> word1;
            case 2 -> word2;
            case 3 -> word3;
            default -> 0L;
        };
    }

    private static void appendDirtyColumns(ArrayList<GAColumnPosition> out, long word, int base) {
        while (word != 0L) {
            int bit = Long.numberOfTrailingZeros(word);
            out.add(GAColumnPosition.fromPackedIndex(base + bit));
            word &= word - 1L;
        }
    }

    private static void setBits(BitSet bits, long word, int base) {
        while (word != 0L) {
            int bit = Long.numberOfTrailingZeros(word);
            bits.set(base + bit);
            word &= word - 1L;
        }
    }
}
