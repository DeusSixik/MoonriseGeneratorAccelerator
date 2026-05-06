package dev.sixik.generator_accelerator.api.patches;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;

public interface GA$CarvingMaskExtension {

    static GA$CarvingMaskExtension get(CarvingMask mask) {
        return (GA$CarvingMaskExtension)mask;
    }

    void bts$addPositionsFast(ChunkPos chunkPos, LongArrayList output);

    void bts$addPositionsRaw(ChunkPos chunkPos, LongScratchBuffer output);
}
