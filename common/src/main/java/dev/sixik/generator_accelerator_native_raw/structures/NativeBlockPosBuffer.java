package dev.sixik.generator_accelerator_native_raw.structures;

import net.minecraft.core.BlockPos;

/**
 * Heap-backed BlockPos buffer retained under the old class name to avoid off-heap/native runtime dependencies.
 */
public final class NativeBlockPosBuffer implements AutoCloseable {
    private long[] values;
    private int size;

    public NativeBlockPosBuffer(int expectedCapacity) {
        this.values = new long[Math.max(1, expectedCapacity)];
    }

    public void add(BlockPos pos) {
        ensureCapacity(this.size + 1);
        this.values[this.size++] = pos.asLong();
    }

    public int size() {
        return this.size;
    }

    public void get(int index, BlockPos.MutableBlockPos out) {
        long packed = this.values[index];
        out.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
    }

    public void set(int index, BlockPos pos) {
        this.values[index] = pos.asLong();
    }

    public void swap(int left, int right, BlockPos.MutableBlockPos first, BlockPos.MutableBlockPos second) {
        if (left == right) {
            return;
        }
        long packed = this.values[left];
        this.values[left] = this.values[right];
        this.values[right] = packed;
    }

    private void ensureCapacity(int required) {
        if (required <= this.values.length) {
            return;
        }
        int capacity = this.values.length;
        while (capacity < required) {
            capacity = Math.max(required, capacity << 1);
        }
        this.values = java.util.Arrays.copyOf(this.values, capacity);
    }

    @Override
    public void close() {
        this.size = 0;
        this.values = new long[0];
    }
}
