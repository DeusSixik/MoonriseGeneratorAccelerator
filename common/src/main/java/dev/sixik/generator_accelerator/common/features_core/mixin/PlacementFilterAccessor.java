package dev.sixik.generator_accelerator.common.features_core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlacementFilter.class)
public interface PlacementFilterAccessor {

    @Invoker("shouldPlace")
    boolean ga$shouldPlace(PlacementContext context, RandomSource random, BlockPos pos);
}
