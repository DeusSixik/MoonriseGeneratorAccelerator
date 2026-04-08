package dev.sixik.generator_accelerator.common.flat_block_structure;

import org.jetbrains.annotations.Nullable;

public interface LevelChunkSection$FlatBlockArray {

    int @Nullable [] bts$getRawBlockData();

    void bts$unpackForGeneration();

    void bts$packAndFreeze();
}
