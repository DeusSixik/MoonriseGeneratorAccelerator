package dev.sixik.generator_accelerator_native_raw.structures;

import dev.sixik.generator_accelerator_native_raw.memory.BlockPosPackedMemory;
import net.minecraft.core.BlockPos;
import net.sixik.javastructg.structs.arrays.NativeObjectArray;

public final class NativeBlockPosBuffer implements AutoCloseable {
    private final NativeObjectArray<BlockPos> values;

    public NativeBlockPosBuffer(int expectedCapacity) {
        this.values = new NativeObjectArray<>(Math.max(1, expectedCapacity), BlockPosPackedMemory.MEMORY);
    }

    public void add(BlockPos pos) {
        this.values.add(pos);
    }

    public int size() {
        return this.values.size();
    }

    public void get(int index, BlockPos.MutableBlockPos out) {
        this.values.get(index, out);
    }

    public void set(int index, BlockPos pos) {
        this.values.set(index, pos);
    }

    public void swap(int left, int right, BlockPos.MutableBlockPos first, BlockPos.MutableBlockPos second) {
        if (left == right) {
            return;
        }
        this.values.get(left, first);
        this.values.get(right, second);
        this.values.set(left, second);
        this.values.set(right, first);
    }

    @Override
    public void close() {
        this.values.freeMemory();
    }
}
