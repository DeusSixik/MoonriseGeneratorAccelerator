package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PlacementFilter.class)
public abstract class MixinPlacementFilter extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    protected abstract boolean shouldPlace(PlacementContext arg, RandomSource arg2, BlockPos arg3);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        BlockPos.MutableBlockPos mPos = SHARED_POS.get().set(packedPos);

        if (this.shouldPlace(context, random, mPos)) {
            output.add(packedPos);
        }
    }
}
