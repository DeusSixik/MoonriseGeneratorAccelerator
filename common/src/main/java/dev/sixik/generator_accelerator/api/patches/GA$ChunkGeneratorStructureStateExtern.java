package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

public interface GA$ChunkGeneratorStructureStateExtern {

    static GA$ChunkGeneratorStructureStateExtern get(ChunkGeneratorStructureState state) {
        return (GA$ChunkGeneratorStructureStateExtern) state;
    }

    Holder<StructureSet>[] getPossibleStructureSetsArray();
}
