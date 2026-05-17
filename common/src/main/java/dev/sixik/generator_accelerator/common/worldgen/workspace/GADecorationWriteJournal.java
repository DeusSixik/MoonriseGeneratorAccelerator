package dev.sixik.generator_accelerator.common.worldgen.workspace;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public final class GADecorationWriteJournal {
    private static final int DEFAULT_CAPACITY = 256;

    private final Long2IntOpenHashMap indexByPos = new Long2IntOpenHashMap(DEFAULT_CAPACITY);
    private long[] packedPositions = new long[DEFAULT_CAPACITY];
    private long[] positions = new long[DEFAULT_CAPACITY];
    private int[] blockIds = new int[DEFAULT_CAPACITY];
    private int size;

    public GADecorationWriteJournal() {
        this.indexByPos.defaultReturnValue(-1);
    }

    public boolean add(int x, int y, int z, int state) {
        if (state == -1) {
            return false;
        }
        long packed = BlockPos.asLong(x, y, z);
        int existing = this.indexByPos.get(packed);
        if (existing >= 0) {
            this.blockIds[existing] = state;
            return true;
        }
        int index = this.size;
        this.ensureCapacity(index + 1);
        this.packedPositions[index] = packed;

        this.positions[index] = BlockPos.asLong(x, y, z);
        this.blockIds[index] = state;
        this.size = index + 1;
        this.indexByPos.put(packed, index);
        return true;
    }

    public Integer blockIdAt(int x, int y, int z) {
        int index = this.indexByPos.get(BlockPos.asLong(x, y, z));
        return index < 0 ? null : this.blockIds[index];
    }

    public BlockState stateAt(long pos) {
        int index = this.indexByPos.get(pos);
        return index < 0 ? null : FastBlockStateCache.getBlockState(this.blockIds[index]);
    }

    public BlockState stateAt(int x, int y, int z) {
        return stateAt(BlockPos.asLong(x, y, z));
    }

    public BlockState stateAt(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return stateAt(pos.asLong());
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
        return BlockPos.getX(this.positions[index]);
    }

    public int y(int index) {
        return BlockPos.getY(this.positions[index]);
    }

    public int z(int index) {
        return BlockPos.getZ(this.positions[index]);
    }

    public BlockState state(int index) {
        return FastBlockStateCache.getBlockState(this.blockIds[index]);
    }

    public int stateId(int index) {
        return blockIds[index];
    }

    public void clear() {
        this.size = 0;
        this.indexByPos.clear();
    }

    private void ensureCapacity(int required) {
        int posL = this.positions.length;

        if (posL >= required) {
            return;
        }
        int next = posL;
        while (next < required) {
            next <<= 1;
        }
        this.packedPositions = Arrays.copyOf(this.packedPositions, next);
        this.positions = Arrays.copyOf(this.positions, next);
        this.blockIds = Arrays.copyOf(this.blockIds, next);
    }
}
