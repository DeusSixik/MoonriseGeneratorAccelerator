package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$RepeatingPlacementAccess;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RepeatingPlacement.class)
public abstract class MixinRepeatingPlacement extends PlacementModifier implements GA$PlacementModifierExtension, GA$RepeatingPlacementAccess {

    @Shadow
    protected abstract int count(RandomSource arg, BlockPos arg2);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public int ga$repeatingCount(RandomSource random, BlockPos.MutableBlockPos pos) {
        return this.count(random, pos);
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        BlockPos.MutableBlockPos mPos = SHARED_POS.get().set(packedPos);
        int count = this.count(random, mPos);

        for (int i = 0; i < count; i++) {
            output.add(packedPos);
        }
    }
}
