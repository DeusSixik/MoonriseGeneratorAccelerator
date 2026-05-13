package dev.sixik.generator_accelerator.common.worldgen.workspace;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public final class GADecorationWriteJournal {
    private static final int DEFAULT_CAPACITY = 256;

    private final Long2IntOpenHashMap indexByPos = new Long2IntOpenHashMap(DEFAULT_CAPACITY);
    private long[] packedPositions = new long[DEFAULT_CAPACITY];
    private int[] x = new int[DEFAULT_CAPACITY];
    private int[] y = new int[DEFAULT_CAPACITY];
    private int[] z = new int[DEFAULT_CAPACITY];
    private int[] blockIds = new int[DEFAULT_CAPACITY];
    private BlockState[] states = new BlockState[DEFAULT_CAPACITY];
    private int size;

    public GADecorationWriteJournal() {
        this.indexByPos.defaultReturnValue(-1);
    }

    public boolean add(int x, int y, int z, BlockState state) {
        if (state == null) {
            return false;
        }
        long packed = BlockPos.asLong(x, y, z);
        int existing = this.indexByPos.get(packed);
        if (existing >= 0) {
            this.blockIds[existing] = Block.getId(state);
            this.states[existing] = state;
            return true;
        }
        int index = this.size;
        this.ensureCapacity(index + 1);
        this.packedPositions[index] = packed;
        this.x[index] = x;
        this.y[index] = y;
        this.z[index] = z;
        this.blockIds[index] = Block.getId(state);
        this.states[index] = state;
        this.size = index + 1;
        this.indexByPos.put(packed, index);
        return true;
    }

    public Integer blockIdAt(int x, int y, int z) {
        int index = this.indexByPos.get(BlockPos.asLong(x, y, z));
        return index < 0 ? null : this.blockIds[index];
    }

    public BlockState stateAt(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        int index = this.indexByPos.get(pos.asLong());
        return index < 0 ? null : this.states[index];
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public long packedPosition(int index) {
        return this.packedPositions[index];
    }

    public int x(int index) {
        return this.x[index];
    }

    public int y(int index) {
        return this.y[index];
    }

    public int z(int index) {
        return this.z[index];
    }

    public BlockState state(int index) {
        return this.states[index];
    }

    public void clear() {
        Arrays.fill(this.states, 0, this.size, null);
        this.size = 0;
        this.indexByPos.clear();
    }

    private void ensureCapacity(int required) {
        if (this.x.length >= required) {
            return;
        }
        int next = this.x.length;
        while (next < required) {
            next <<= 1;
        }
        this.packedPositions = Arrays.copyOf(this.packedPositions, next);
        this.x = Arrays.copyOf(this.x, next);
        this.y = Arrays.copyOf(this.y, next);
        this.z = Arrays.copyOf(this.z, next);
        this.blockIds = Arrays.copyOf(this.blockIds, next);
        this.states = Arrays.copyOf(this.states, next);
    }
}
