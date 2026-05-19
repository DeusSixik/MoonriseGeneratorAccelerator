package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import dev.sixik.generator_accelerator.common.structures.StructurePoolElementCache;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(StructurePoolElement.class)
public class MixinStructurePoolElement$cache_jigsawBlocks implements StructurePoolElementCache {

    @Override
    public List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(
            StructureTemplateManager manager,
            BlockPos pos,
            Rotation rotation,
            RandomSource random
    ) {
        return ((StructurePoolElement) (Object) this).getShuffledJigsawBlocks(manager, pos, rotation, random);
    }
}
