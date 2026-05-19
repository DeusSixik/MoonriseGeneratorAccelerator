package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.util.List;

public interface StructureTemplatePoolCache {
    List<StructurePoolElement> bts$getCachedShuffledTemplates(RandomSource random);
}
