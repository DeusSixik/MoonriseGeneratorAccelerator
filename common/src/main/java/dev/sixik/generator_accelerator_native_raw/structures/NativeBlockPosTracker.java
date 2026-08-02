package dev.sixik.generator_accelerator_native_raw.structures;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Heap-backed BlockPos tracker retained under the old class name to avoid off-heap/native runtime dependencies.
 */
public final class NativeBlockPosTracker implements AutoCloseable {
    private final LongOpenHashSet membership;
    private long[] recorded;
    private int recordedSize;

    public NativeBlockPosTracker(int expectedCapacity) {
        int capacity = Math.max(1, expectedCapacity);
        this.membership = new LongOpenHashSet(capacity);
        this.recorded = new long[capacity];
    }

    public boolean add(BlockPos pos) {
        long packed = pos.asLong();
        if (!this.membership.add(packed)) {
            return false;
        }
        ensureRecordedCapacity(this.recordedSize + 1);
        this.recorded[this.recordedSize++] = packed;
        return true;
    }

    public boolean remove(BlockPos pos) {
        return this.membership.remove(pos.asLong());
    }

    public boolean contains(BlockPos pos) {
        return this.membership.contains(pos.asLong());
    }

    public boolean isEmpty() {
        return this.membership.isEmpty();
    }

    public int recordedSize() {
        return this.recordedSize;
    }

    public void getRecorded(int index, BlockPos.MutableBlockPos out) {
        long packed = this.recorded[index];
        out.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
    }

    public void clear() {
        this.membership.clear();
        this.recordedSize = 0;
    }

    private void ensureRecordedCapacity(int required) {
        if (required <= this.recorded.length) {
            return;
        }
        int capacity = this.recorded.length;
        while (capacity < required) {
            capacity = Math.max(required, capacity << 1);
        }
        this.recorded = java.util.Arrays.copyOf(this.recorded, capacity);
    }

    @Override
    public void close() {
        this.membership.clear();
        this.recordedSize = 0;
        this.recorded = new long[0];
    }
}
