package dev.sixik.generator_accelerator.common.aquifer;

import java.util.Arrays;

/**
 * Primitive fluid status mirror. The Minecraft adapter owns object state;
 * hot-path pair checks use only level/kind arrays from this grid.
 */
public final class GAAquiferFluidGrid {
    public static final byte KIND_UNKNOWN = -1;
    public static final byte KIND_AIR = 0;
    public static final byte KIND_WATER = 1;
    public static final byte KIND_LAVA = 2;
    public static final byte KIND_OTHER = 3;

    final int[] level;
    final byte[] kind;

    public GAAquiferFluidGrid(int size) {
        this.level = new int[size];
        this.kind = new byte[size];
        Arrays.fill(this.kind, KIND_UNKNOWN);
    }

    public boolean has(int index) {
        return this.kind[index] != KIND_UNKNOWN;
    }

    public void set(int index, int level, byte kind) {
        this.level[index] = level;
        this.kind[index] = kind;
    }

    public int level(int index) {
        return this.level[index];
    }

    public byte kind(int index) {
        return this.kind[index];
    }

    public byte kindAt(int index, int y) {
        return y < this.level[index] ? this.kind[index] : KIND_AIR;
    }
}
