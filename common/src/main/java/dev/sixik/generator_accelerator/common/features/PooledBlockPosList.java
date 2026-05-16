package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.BlockPos;

import java.util.AbstractList;
import java.util.RandomAccess;

public final class PooledBlockPosList extends AbstractList<BlockPos> implements RandomAccess {
    private BlockPos.MutableBlockPos[] values;
    private int size;

    public PooledBlockPosList(int initialCapacity) {
        this.values = new BlockPos.MutableBlockPos[Math.max(1, initialCapacity)];
    }

    public void addCoords(int x, int y, int z) {
        this.ensureCapacity(this.size + 1);
        BlockPos.MutableBlockPos pos = this.values[this.size];
        if (pos == null) {
            pos = new BlockPos.MutableBlockPos();
            this.values[this.size] = pos;
        }
        pos.set(x, y, z);
        this.size++;
    }

    @Override
    public boolean add(BlockPos pos) {
        this.addCoords(pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    @Override
    public BlockPos get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + this.size);
        }
        return this.values[index];
    }

    @Override
    public int size() {
        return this.size;
    }

    private void ensureCapacity(int required) {
        if (required <= this.values.length) {
            return;
        }
        int next = this.values.length;
        while (next < required) {
            next <<= 1;
        }
        BlockPos.MutableBlockPos[] grown = new BlockPos.MutableBlockPos[next];
        System.arraycopy(this.values, 0, grown, 0, this.values.length);
        this.values = grown;
    }
}
