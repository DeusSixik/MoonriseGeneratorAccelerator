package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.strucutres.pools;

import dev.sixik.moonrisegeneratoraccelerator.common.level.strucutres.StructurePoolElementCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.apache.commons.lang3.NotImplementedException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(SinglePoolElement.class)
public abstract class MixinSinglePoolElement$cache_jigsawBlocks extends StructurePoolElement implements StructurePoolElementCache {

    @Shadow
    protected abstract StructureTemplate getTemplate(StructureTemplateManager structureTemplateManager);

    @Shadow
    static void sortBySelectionPriority(List<StructureTemplate.StructureBlockInfo> list) {
        throw new NotImplementedException();
    }

    protected MixinSinglePoolElement$cache_jigsawBlocks(StructureTemplatePool.Projection projection) {
        super(projection);
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(StructureTemplateManager manager, BlockPos pos, Rotation rotation, RandomSource random) {
        final var bts$jigsawCache = bts$getJigsawCache();

        List<StructureTemplate.StructureBlockInfo> cached = bts$jigsawCache.get(rotation);

        if (cached == null) {
            final StructureTemplate template = this.getTemplate(manager);

            /*
                Filtering blocks (an expensive operation)
             */
            final ObjectArrayList<StructureTemplate.StructureBlockInfo> blocks =
                    template.filterBlocks(BlockPos.ZERO,
                            new StructurePlaceSettings().setRotation(rotation), Blocks.JIGSAW, true
                    );

            /*
                Sorting once (calling sortBySelectionPriority)
             */
            sortBySelectionPriority(blocks);

            /*
                We save an immutable list to the cache
             */

            cached = new ObjectImmutableList<>(blocks);
            bts$jigsawCache.put(rotation, cached);
        }

        /*
            Creating a mutable copy for mixing
         */
        final ObjectArrayList<StructureTemplate.StructureBlockInfo> result =
                new ObjectArrayList<>(cached);

        /*
            We mix only the elements with the same priority.
         */
        Util.shuffle(result, random);
        sortBySelectionPriority(result);

        if (pos.equals(BlockPos.ZERO)) {
            return result;
        }

        /*
            Applying the BlockPos offset
         */
        final ObjectArrayList<StructureTemplate.StructureBlockInfo> offsetResult =
                new ObjectArrayList<>(result.size());
        for (StructureTemplate.StructureBlockInfo info : result) {
            offsetResult.add(new StructureTemplate.StructureBlockInfo(info.pos().offset(pos), info.state(), info.nbt()));
        }
        return offsetResult;
    }

}
