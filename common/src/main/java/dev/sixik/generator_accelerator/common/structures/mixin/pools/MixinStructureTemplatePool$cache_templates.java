package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import dev.sixik.generator_accelerator.common.structures.StructurePlacementShuffler;
import dev.sixik.generator_accelerator.common.structures.StructureTemplatePoolCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.Util;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(StructureTemplatePool.class)
public class MixinStructureTemplatePool$cache_templates implements StructureTemplatePoolCache {
    @Shadow
    @Final
    private ObjectArrayList<StructurePoolElement> templates;

    @Unique
    private volatile StructurePoolElement[] bts$templateArray;

    @Override
    public List<StructurePoolElement> bts$getCachedShuffledTemplates(RandomSource random) {
        int size = this.templates.size();
        // Large weighted pools are faster on vanilla ObjectArrayList bulk copy; keep the fast path bounded.
        if (!StructurePlacementShuffler.shouldUseDeferredTemplateShuffle(size)) {
            return Util.shuffledCopy(this.templates, random);
        }

        StructurePoolElement[] array = this.bts$templateArray;
        if (!this.ga$templateArrayMatches(array, size)) {
            array = this.templates.toArray(new StructurePoolElement[size]);
            this.bts$templateArray = array;
        }
        return StructurePlacementShuffler.shuffledTemplates(array, random);
    }

    @Unique
    private boolean ga$templateArrayMatches(StructurePoolElement[] array, int size) {
        if (array == null || array.length != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (array[i] != this.templates.get(i)) {
                return false;
            }
        }
        return true;
    }
}
