package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.List;

/**
 * Chunk-local structure-start lookup that can check a whole structure set
 * under one lock instead of calling ChunkAccess#getStartForStructure repeatedly.
 */
public interface StructureStartFastLookup {
    boolean ga$hasAnyStartFor(List<StructureSet.StructureSelectionEntry> entries);

    boolean ga$hasStartFor(Structure structure);
}
