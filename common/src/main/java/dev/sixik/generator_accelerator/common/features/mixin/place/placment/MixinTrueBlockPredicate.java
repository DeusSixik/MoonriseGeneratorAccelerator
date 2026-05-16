package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.TrueBlockPredicate")
public abstract class MixinTrueBlockPredicate implements GA$BlockPredicateExtension {
    @Override
    public boolean ga$testRaw(WorldGenLevel level, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        return true;
    }

    /**
     * @author Sixik
     * @reason Keep the true predicate branchless and allocation-free.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        return true;
    }
}
