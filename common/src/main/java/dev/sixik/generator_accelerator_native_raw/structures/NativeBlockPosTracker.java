package dev.sixik.generator_accelerator_native_raw.structures;

import dev.sixik.generator_accelerator_native_raw.memory.BlockPosPackedMemory;
import net.minecraft.core.BlockPos;
import net.sixik.javastructg.structs.arrays.NativeObjectArray;
import net.sixik.javastructg.structs.sets.NativeObjectSet;

public final class NativeBlockPosTracker implements AutoCloseable {
    private final NativeObjectSet<BlockPos> membership;
    private final NativeObjectArray<BlockPos> recorded;

    public NativeBlockPosTracker(int expectedCapacity) {
        int capacity = Math.max(1, expectedCapacity);
        this.membership = new NativeObjectSet<>(capacity, BlockPosPackedMemory.MEMORY, BlockPos.MutableBlockPos::new);
        this.recorded = new NativeObjectArray<>(capacity, BlockPosPackedMemory.MEMORY);
    }

    public boolean add(BlockPos pos) {
        if (!this.membership.add(pos)) {
            return false;
        }
        this.recorded.add(pos);
        return true;
    }

    public boolean remove(BlockPos pos) {
        return this.membership.remove(pos);
    }

    public boolean contains(BlockPos pos) {
        return this.membership.contains(pos);
    }

    public int recordedSize() {
        return this.recorded.size();
    }

    public void getRecorded(int index, BlockPos.MutableBlockPos out) {
        this.recorded.get(index, out);
    }

    @Override
    public void close() {
        this.recorded.freeMemory();
        this.membership.freeMemory();
    }
}
