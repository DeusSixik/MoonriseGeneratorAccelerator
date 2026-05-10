package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PlacementFilter.class)
public abstract class MixinPlacementFilter extends PlacementModifier implements GA$PlacementModifierExtension, GA$PlacementFilterAccess {

    @Shadow
    protected abstract boolean shouldPlace(PlacementContext arg, RandomSource arg2, BlockPos arg3);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public boolean ga$shouldPlace(PlacementContext context, RandomSource random, BlockPos.MutableBlockPos pos) {
        return this.shouldPlace(context, random, pos);
    }

    @Override
    public boolean ga$shouldPlaceRaw(PlacementContext context, RandomSource random, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        return this.shouldPlace(context, random, scratch.set(x, y, z));
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        BlockPos.MutableBlockPos mPos = SHARED_POS.get().set(packedPos);

        if (this.shouldPlace(context, random, mPos)) {
            output.add(packedPos);
        }
    }
}
