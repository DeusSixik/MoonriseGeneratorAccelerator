package dev.sixik.generator_accelerator.common.features.mixin.compats.confluence;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.confluence.mod.api.SecretFlagMatcher;
import org.confluence.mod.common.worldgen.SecretFlagPlacement;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SecretFlagPlacement.class)
public abstract class Confluence$SecretFlagPlacementMixin extends PlacementModifier implements SecretFlagMatcher, GA$PlacementModifierExtension {

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        if(matchesSecretFlag()) {
            output.add(packedPos);
        }
    }
}
