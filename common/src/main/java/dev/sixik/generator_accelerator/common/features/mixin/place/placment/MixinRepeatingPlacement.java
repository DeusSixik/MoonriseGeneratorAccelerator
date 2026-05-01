package dev.sixik.generator_accelerator.common.features.mixin.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RepeatingPlacement.class)
public abstract class MixinRepeatingPlacement extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    protected abstract int count(RandomSource arg, BlockPos arg2);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        BlockPos.MutableBlockPos mPos = SHARED_POS.get().set(packedPos);
        int count = this.count(random, mPos);

        for (int i = 0; i < count; i++) {
            output.add(packedPos);
        }
    }
}
