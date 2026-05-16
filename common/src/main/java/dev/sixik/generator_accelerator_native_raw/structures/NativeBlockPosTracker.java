package dev.sixik.generator_accelerator_native_raw.structures;

import dev.sixik.generator_accelerator_native_raw.memory.BlockPosPackedMemory;
import net.minecraft.core.BlockPos;
import net.sixik.javastructg.structs.arrays.NativeObjectArray;
import net.sixik.javastructg.structs.sets.NativeLongSet;

public final class NativeBlockPosTracker implements AutoCloseable {
    private final NativeLongSet membership;
    private final NativeObjectArray<BlockPos> recorded;
    private int recordedSize;

    public NativeBlockPosTracker(int expectedCapacity) {
        int capacity = Math.max(1, expectedCapacity);
        this.membership = new NativeLongSet(capacity);
        this.recorded = new NativeObjectArray<>(capacity, BlockPosPackedMemory.MEMORY);
    }

    public boolean add(BlockPos pos) {
        if (!this.membership.add(pos.asLong())) {
            return false;
        }
        if (this.recordedSize < this.recorded.size()) {
            this.recorded.set(this.recordedSize, pos);
        } else {
            this.recorded.add(pos);
        }
        this.recordedSize++;
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
        this.recorded.get(index, out);
    }

    public void clear() {
        this.membership.clear();
        this.recordedSize = 0;
    }

    @Override
    public void close() {
        this.recordedSize = 0;
        this.recorded.freeMemory();
        this.membership.freeMemory();
    }
}
