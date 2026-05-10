package dev.sixik.generator_accelerator.api.patches;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

public interface GA$StructureManagerExtension {
    @Nullable
    ObjectArrayList<StructureStart> ga$startsForStructureFast(SectionPos sectionPos, Structure structure);
}
