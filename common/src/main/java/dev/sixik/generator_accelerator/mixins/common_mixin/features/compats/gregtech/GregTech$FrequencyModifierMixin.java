package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.gregtech;

import com.gregtechceu.gtceu.api.data.worldgen.modifier.FrequencyModifier;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = FrequencyModifier.class, remap = false)
public abstract class GregTech$FrequencyModifierMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Final
    @Shadow
    private float frequency;

    @Shadow public abstract int getCount(float frequency, RandomSource random);

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        if (this.getCount(this.frequency, random) > 0) {
            output.add(packedPos);
        }
    }
}
