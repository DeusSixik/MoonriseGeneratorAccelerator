package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.placment;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PlacementModifier.class)
public class MixinPlacementModifier implements GA$PlacementModifierExtension {
}
