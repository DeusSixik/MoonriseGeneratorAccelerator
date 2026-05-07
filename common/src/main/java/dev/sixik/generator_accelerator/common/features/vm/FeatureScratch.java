package dev.sixik.generator_accelerator.common.features.vm;

import net.minecraft.core.BlockPos;

public final class FeatureScratch {
    private LongScratchBuffer[] buffers = new LongScratchBuffer[] {
            new LongScratchBuffer(64),
            new LongScratchBuffer(64),
            new LongScratchBuffer(64),
            new LongScratchBuffer(64)
    };
    private BlockPos.MutableBlockPos[] mutablePositions = new BlockPos.MutableBlockPos[] {
            new BlockPos.MutableBlockPos(),
            new BlockPos.MutableBlockPos(),
            new BlockPos.MutableBlockPos(),
            new BlockPos.MutableBlockPos()
    };
    private ReusableFeaturePlaceContext[] featurePlaceContexts = new ReusableFeaturePlaceContext[] {
            new ReusableFeaturePlaceContext(),
            new ReusableFeaturePlaceContext(),
            new ReusableFeaturePlaceContext(),
            new ReusableFeaturePlaceContext()
    };
    private final BiomeFilterScratch biomeFilterScratch = new BiomeFilterScratch();

    LongScratchBuffer buffer(int depth) {
        if (depth >= this.buffers.length) {
            grow(depth + 1);
        }
        LongScratchBuffer buffer = this.buffers[depth];
        buffer.clear();
        return buffer;
    }

    BlockPos.MutableBlockPos mutablePos(int depth) {
        if (depth >= this.mutablePositions.length) {
            grow(depth + 1);
        }
        return this.mutablePositions[depth];
    }

    ReusableFeaturePlaceContext featurePlaceContext(int depth) {
        if (depth >= this.featurePlaceContexts.length) {
            grow(depth + 1);
        }
        return this.featurePlaceContexts[depth];
    }

    BiomeFilterScratch biomeFilterScratch() {
        return this.biomeFilterScratch;
    }

    void reset() {
        // Buffers clear on acquire by depth; release must stay zero-cost in hot path.
        this.biomeFilterScratch.clear();
    }

    private void grow(int capacity) {
        int oldLength = this.buffers.length;
        int nextLength = oldLength + (oldLength >> 1) + 1;
        if (nextLength < capacity) {
            nextLength = capacity;
        }

        LongScratchBuffer[] next = new LongScratchBuffer[nextLength];
        System.arraycopy(this.buffers, 0, next, 0, oldLength);
        for (int i = oldLength; i < nextLength; i++) {
            next[i] = new LongScratchBuffer(64);
        }
        this.buffers = next;

        BlockPos.MutableBlockPos[] nextPositions = new BlockPos.MutableBlockPos[nextLength];
        System.arraycopy(this.mutablePositions, 0, nextPositions, 0, this.mutablePositions.length);
        for (int i = this.mutablePositions.length; i < nextLength; i++) {
            nextPositions[i] = new BlockPos.MutableBlockPos();
        }
        this.mutablePositions = nextPositions;

        ReusableFeaturePlaceContext[] nextContexts = new ReusableFeaturePlaceContext[nextLength];
        System.arraycopy(this.featurePlaceContexts, 0, nextContexts, 0, this.featurePlaceContexts.length);
        for (int i = this.featurePlaceContexts.length; i < nextLength; i++) {
            nextContexts[i] = new ReusableFeaturePlaceContext();
        }
        this.featurePlaceContexts = nextContexts;
    }
}
