package dev.sixik.generator_accelerator.common.features.mixin.place;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.*;

import java.util.List;
import java.util.stream.Stream;


@Deprecated
@Mixin(PlacedFeature.class)
public class FastPlacedFeatureMixin {

    @Shadow @Final private List<PlacementModifier> placement;
    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    /**
     * @author Sixik
     * @reason Eliminating Stream.flatMap overhead while preserving vanilla Depth-First ordering
     */
    @Overwrite
    public final boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos pos) {
        MutableBoolean success = new MutableBoolean();
        this.bts$placeRecursively(context, random, pos, 0, success);
        return success.isTrue();
    }

    @Unique
    private void bts$placeRecursively(PlacementContext context, RandomSource random, BlockPos pos, int modifierIndex, MutableBoolean success) {
        if (modifierIndex >= this.placement.size()) {

            final ConfiguredFeature<?, ? extends Feature<?>> feature = this.feature.value();
            if (feature.place(context.getLevel(), context.generator(), random, pos)) {
                success.setTrue();
            }
            return;
        }

        PlacementModifier modifier = this.placement.get(modifierIndex);

        Stream<BlockPos> stream = modifier.getPositions(context, random, pos);
        stream.forEach(nextPos -> {
            this.bts$placeRecursively(context, random, nextPos, modifierIndex + 1, success);
        });
    }
}