package dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith;

import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import net.minecraft.core.Holder;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$SubBiomeRequestSet", remap = false)
public abstract class Biolith$SubBiomeRequestSet$fast_iter {
    @Unique
    private static final ThreadLocal<ReusableListIterator> GA$SUB_BIOME_ITERATOR =
            ThreadLocal.withInitial(ReusableListIterator::new);

    /**
     * @author Sixik
     * @reason Avoid ImmutableCollections.ListItr allocation on every Biolith sub-biome
     * query in large biome packs.
     */
    @Redirect(
            method = "selectSubBiome(Lcom/terraformersmc/biolith/api/biome/BiolithFittestNodes;Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/util/InclusiveRange;D)Lcom/terraformersmc/biolith/impl/biome/DimensionBiomePlacement$SubBiomeRequest;",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"),
            remap = false
    )
    private Iterator<?> ga$selectSubBiomeWithoutIterator(List<?> list) {
        return GA$SUB_BIOME_ITERATOR.get().reset(list);
    }

    @Unique
    private static final class ReusableListIterator implements Iterator<Object> {
        private List<?> list;
        private int index;
        private int size;

        private ReusableListIterator reset(List<?> list) {
            this.list = list;
            this.index = 0;
            this.size = list.size();
            return this;
        }

        @Override
        public boolean hasNext() {
            return this.index < this.size;
        }

        @Override
        public Object next() {
            int current = this.index;
            if (current >= this.size) {
                throw new NoSuchElementException();
            }
            this.index = current + 1;
            return this.list.get(current);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
