package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.function.Consumer;

public final class StructureStartReferenceFanout {
    private StructureStartReferenceFanout() {
    }

    public static void fillStarts(LevelAccessor level, Structure structure, LongSet references, Consumer<StructureStart> consumer) {
        LongIterator iterator = references.iterator();
        while (iterator.hasNext()) {
            long packedChunkPos = iterator.nextLong();
            ChunkAccess chunk = level.getChunk(
                    ChunkPos.getX(packedChunkPos),
                    ChunkPos.getZ(packedChunkPos),
                    ChunkStatus.STRUCTURE_STARTS
            );
            StructureStart start = chunk.getStartForStructure(structure);
            if (start != null && start.isValid()) {
                consumer.accept(start);
            }
        }
    }
}
