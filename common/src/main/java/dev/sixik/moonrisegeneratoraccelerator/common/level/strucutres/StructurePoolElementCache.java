package dev.sixik.moonrisegeneratoraccelerator.common.level.strucutres;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Map;

public interface StructurePoolElementCache {

    Map<Rotation, List<StructureTemplate.StructureBlockInfo>> bts$getJigsawCache();

    List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(StructureTemplateManager manager, BlockPos pos, Rotation rotation, RandomSource random);
}
