package dev.sixik.generator_accelerator.common.surface.mixin.blockpredicates;

import dev.sixik.generator_accelerator.common.surface.CombiningPredicatePrimitiveArray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.AllOfPredicate")
public abstract class MixinAllOfPredicate$primitive_array implements CombiningPredicatePrimitiveArray {

    /**
     * @author Sixik
     * @reason Eliminating Iterator allocations and virtual calls in the hot block checking loop
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        BlockPredicate[] fastArray = bts$getPrimitiveArray();

        for (int i = 0; i < fastArray.length; i++) {
            if (!fastArray[i].test(level, pos)) {
                return false;
            }
        }
        return true;
    }
}
