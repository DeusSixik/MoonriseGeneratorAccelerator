package dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures;

import com.telepathicgrunt.repurposedstructures.world.placements.MinDistanceFromWorldOriginPlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinDistanceFromWorldOriginPlacement.class)
public abstract class Repurposedstructures$MinDistanceFromWorldOriginPlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private int minDistanceFromWorldOrigin;

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos);
        int y = BlockPos.getY(packedPos);
        int z = BlockPos.getZ(packedPos);


        int manhattanDist = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (manhattanDist > this.minDistanceFromWorldOrigin) {
            output.add(packedPos);
        }
    }
}
