package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$BlockPredicateExtension;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockPredicateFilter.class)
public abstract class MixinBlockPredicateFilter extends PlacementFilter implements GA$PlacementFilterAccess {
    @Shadow
    @Final
    private BlockPredicate predicate;

    @Override
    public boolean ga$shouldPlace(PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos) {
        return GA$BlockPredicateExtension.testRaw(
                this.predicate,
                context.getLevel(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                pos
        );
    }

    @Override
    public boolean ga$shouldPlaceRaw(
            PlacementContext context,
            RandomSource random,
            int x,
            int y,
            int z,
            BlockPos.MutableBlockPos scratch
    ) {
        return GA$BlockPredicateExtension.testRaw(this.predicate, context.getLevel(), x, y, z, scratch);
    }
}
