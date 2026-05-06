package dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures;

import com.telepathicgrunt.repurposedstructures.world.placements.MinusEightPlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinusEightPlacement.class)
public abstract class Repurposedstructures$MinusEightPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos) - 8;
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos) - 8;

        output.add(BlockPos.asLong(x, y, z));
    }
}
