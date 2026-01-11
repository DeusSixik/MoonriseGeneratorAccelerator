package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.strucutres.pools;

import dev.sixik.moonrisegeneratoraccelerator.common.level.strucutres.StructurePoolElementCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Mixin(StructurePoolElement.class)
public class MixinStructurePoolElement$cache_jigsawBlocks implements StructurePoolElementCache {

    @Unique
    private final Map<Rotation, List<StructureTemplate.StructureBlockInfo>> bts$jigsawCache = new EnumMap<>(Rotation.class);

    @Override
    public Map<Rotation, List<StructureTemplate.StructureBlockInfo>> bts$getJigsawCache() {
        return bts$jigsawCache;
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> bts$getCachedJigsawBlocks(StructureTemplateManager manager, BlockPos pos, Rotation rotation, RandomSource random) {
        /*
            We cache blocks relative to BlockPos.ZERO and without shuffling,
            and then apply the offset and shuffle on the fly
         */
        List<StructureTemplate.StructureBlockInfo> cached = bts$jigsawCache.get(rotation);

        if (cached == null) {
            cached = ((StructurePoolElement)(Object)this).getShuffledJigsawBlocks(manager, BlockPos.ZERO, rotation, random);
            bts$jigsawCache.put(rotation, cached);
        }

        /*
            Creating a new list from the cache (so that shuffling does not spoil the cache)
         */
        final List<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(cached);

        /*
            Mixing (vanilla behavior)
         */
        Collections.shuffle(result, new java.util.Random(random.nextLong()));

        /*
            We apply the BlockPos offset (since the cache was for ZERO)
         */
        if (!pos.equals(BlockPos.ZERO)) {
            final List<StructureTemplate.StructureBlockInfo> offsetResult = new ObjectArrayList<>(result.size());

            /*
                Since this is a hot path, we use a loop through the index as
                it will be faster than through an iterator.
             */
            for (int i = 0; i < result.size(); i++) {
                final StructureTemplate.StructureBlockInfo info = result.get(i);
                offsetResult.add(new StructureTemplate.StructureBlockInfo(
                        info.pos().offset(pos), info.state(), info.nbt()
                ));
            }
            return offsetResult;
        }

        return result;
    }
}
