package dev.sixik.generator_accelerator.common.features.vm;

import java.util.Arrays;

public final class LongScratchBuffer {
    private long[] values;
    private int size;

    LongScratchBuffer(int initialCapacity) {
        this.values = new long[initialCapacity];
    }

    public void add(long value) {
        int index = this.size;
        if (index == this.values.length) {
            grow(index + 1);
        }
        this.values[index] = value;
        this.size = index + 1;
    }

    public void addRepeated(long value, int count) {
        if (count <= 0) {
            return;
        }
        int index = this.size;
        int nextSize = index + count;
        if (nextSize > this.values.length) {
            grow(nextSize);
        }
        Arrays.fill(this.values, index, nextSize, value);
        this.size = nextSize;
    }

    public long getLong(int index) {
        return this.values[index];
    }

    public long[] elements() {
        return this.values;
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public void clear() {
        this.size = 0;
    }

    private void grow(int capacity) {
        int next = this.values.length + (this.values.length >> 1) + 1;
        if (next < capacity) {
            next = capacity;
        }
        this.values = Arrays.copyOf(this.values, next);
    }
}
