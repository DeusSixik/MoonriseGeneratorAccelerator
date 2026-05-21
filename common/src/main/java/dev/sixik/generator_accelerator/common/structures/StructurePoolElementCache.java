package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

public interface StructurePoolElementCache {

    List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(StructureTemplateManager manager, BlockPos pos, Rotation rotation, RandomSource random);
}
