package dev.sixik.generator_accelerator.common.features_core.mixin;

import dev.sixik.generator_accelerator.common.features_core.utils.PlacedTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.*;

import java.util.List;

@Mixin(PlacedFeature.class)
public class MixinPlacedFeature$change_place {

    @Shadow @Final private List<PlacementModifier> placement;
    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    /**
     * @author Sixik
     * @reason Fast non-stream placement loop
     */
    @Overwrite
    public final boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos origin) {
        return placeStep(context, random, origin, 0);
    }

    @Unique
    private boolean placeStep(PlacementContext context, RandomSource random, BlockPos pos, int index) {
        if (index >= this.placement.size()) {
            return this.feature.value().place(context.getLevel(), context.generator(), random, pos);
        }

        final PlacementModifier modifier = this.placement.get(index);
        final int nextIndex = index + 1;

        // PlacementFilter (BiomeFilter, BlockPredicateFilter and other)
        // Remove 70%+ allocations
        if (modifier instanceof PlacementFilter filter) {
            if (((PlacementFilterAccessor) filter).ga$shouldPlace(context, random, pos)) {
                return placeStep(context, random, pos, nextIndex);
            }
            return false;
        }

        // Other filters
        // DFS Steps
        final PlacedTracker tracker = new PlacedTracker();

        modifier.getPositions(context, random, pos).forEach(nextPos -> {
            if (placeStep(context, random, nextPos, nextIndex)) {
                tracker.placed = true;
            }
        });

        return tracker.placed;
    }
}