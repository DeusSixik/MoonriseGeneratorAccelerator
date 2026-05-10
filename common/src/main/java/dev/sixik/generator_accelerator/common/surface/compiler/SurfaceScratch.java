package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.BitSet;

public final class SurfaceScratch {
    private Mask4096[] transientMasks = new Mask4096[32];
    private int transientTop;

    private Mask4096[] conditionMasks = new Mask4096[16];
    private int[] conditionGenerations = new int[16];
    private long[][] columnConditionMasks = new long[16][];
    private int[] columnConditionGenerations = new int[16];
    private int[][] intervalMinYCache = new int[16][];
    private int[] intervalConditionGenerations = new int[16];
    private int conditionGeneration = 1;

    public final Mask4096 stoneMask = new Mask4096();
    public final Mask4096 activeMask = new Mask4096();
    public final BitSet bridgeBitSet = new BitSet(Mask4096.BIT_COUNT);
    public final int[] previousSectionBottomDepths = new int[256];
    public final long[] activeColumns = new long[4];
    public final long[] candidateColumns = new long[4];
    public final long[] layeredColumns = new long[Mask4096.WORD_COUNT];
    public final int[] intervalMinY = new int[256];
    public final String[] biomeNamespaces = new String[256];
    public final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    public final BlockPos.MutableBlockPos postProcessPos = new BlockPos.MutableBlockPos();

    public SurfaceScratch() {
        for (int i = 0; i < this.transientMasks.length; i++) {
            this.transientMasks[i] = new Mask4096();
        }
        for (int i = 0; i < this.conditionMasks.length; i++) {
            this.conditionMasks[i] = new Mask4096();
        }
        for (int i = 0; i < this.columnConditionMasks.length; i++) {
            this.columnConditionMasks[i] = new long[4];
        }
        for (int i = 0; i < this.intervalMinYCache.length; i++) {
            this.intervalMinYCache[i] = new int[256];
        }
    }

    public void beginSection() {
        this.transientTop = 0;
        this.conditionGeneration++;
        if (this.conditionGeneration == 0) {
            Arrays.fill(this.conditionGenerations, 0);
            this.conditionGeneration = 1;
        }
    }

    public int mark() {
        return this.transientTop;
    }

    public void restore(int mark) {
        this.transientTop = mark;
    }

    public Mask4096 pushMask() {
        if (this.transientTop >= this.transientMasks.length) {
            growTransientMasks();
        }
        Mask4096 mask = this.transientMasks[this.transientTop++];
        mask.clear();
        return mask;
    }

    public Mask4096 pushMaskForOverwrite() {
        if (this.transientTop >= this.transientMasks.length) {
            growTransientMasks();
        }
        return this.transientMasks[this.transientTop++];
    }

    public int reserveMasks(int count) {
        int base = this.transientTop;
        for (int i = 0; i < count; i++) {
            pushMask();
        }
        return base;
    }

    public Mask4096 transientMask(int index) {
        return this.transientMasks[index];
    }

    public Mask4096 conditionMask(int slot) {
        ensureConditionSlot(slot);
        return this.conditionMasks[slot];
    }

    public boolean isConditionValid(int slot) {
        ensureConditionSlot(slot);
        return this.conditionGenerations[slot] == this.conditionGeneration;
    }

    public void markConditionValid(int slot) {
        ensureConditionSlot(slot);
        this.conditionGenerations[slot] = this.conditionGeneration;
    }

    public long[] columnConditionMask(int slot) {
        ensureColumnConditionSlot(slot);
        return this.columnConditionMasks[slot];
    }

    public boolean isColumnConditionValid(int slot) {
        ensureColumnConditionSlot(slot);
        return this.columnConditionGenerations[slot] == this.conditionGeneration;
    }

    public void markColumnConditionValid(int slot) {
        ensureColumnConditionSlot(slot);
        this.columnConditionGenerations[slot] = this.conditionGeneration;
    }

    public int[] intervalMinYCache(int slot) {
        ensureIntervalConditionSlot(slot);
        return this.intervalMinYCache[slot];
    }

    public boolean isIntervalConditionValid(int slot) {
        ensureIntervalConditionSlot(slot);
        return this.intervalConditionGenerations[slot] == this.conditionGeneration;
    }

    public void markIntervalConditionValid(int slot) {
        ensureIntervalConditionSlot(slot);
        this.intervalConditionGenerations[slot] = this.conditionGeneration;
    }

    private void growTransientMasks() {
        int oldLength = this.transientMasks.length;
        this.transientMasks = Arrays.copyOf(this.transientMasks, oldLength << 1);
        for (int i = oldLength; i < this.transientMasks.length; i++) {
            this.transientMasks[i] = new Mask4096();
        }
    }

    private void ensureConditionSlot(int slot) {
        if (slot < this.conditionMasks.length) {
            return;
        }
        int oldLength = this.conditionMasks.length;
        int newLength = oldLength;
        while (slot >= newLength) {
            newLength <<= 1;
        }
        this.conditionMasks = Arrays.copyOf(this.conditionMasks, newLength);
        this.conditionGenerations = Arrays.copyOf(this.conditionGenerations, newLength);
        for (int i = oldLength; i < newLength; i++) {
            this.conditionMasks[i] = new Mask4096();
        }
    }

    private void ensureColumnConditionSlot(int slot) {
        if (slot < this.columnConditionMasks.length) {
            return;
        }
        int oldLength = this.columnConditionMasks.length;
        int newLength = oldLength;
        while (slot >= newLength) {
            newLength <<= 1;
        }
        this.columnConditionMasks = Arrays.copyOf(this.columnConditionMasks, newLength);
        this.columnConditionGenerations = Arrays.copyOf(this.columnConditionGenerations, newLength);
        for (int i = oldLength; i < newLength; i++) {
            this.columnConditionMasks[i] = new long[4];
        }
    }

    private void ensureIntervalConditionSlot(int slot) {
        if (slot < this.intervalMinYCache.length) {
            return;
        }
        int oldLength = this.intervalMinYCache.length;
        int newLength = oldLength;
        while (slot >= newLength) {
            newLength <<= 1;
        }
        this.intervalMinYCache = Arrays.copyOf(this.intervalMinYCache, newLength);
        this.intervalConditionGenerations = Arrays.copyOf(this.intervalConditionGenerations, newLength);
        for (int i = oldLength; i < newLength; i++) {
            this.intervalMinYCache[i] = new int[256];
        }
    }
}
