package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.gregtech;

import com.gregtechceu.gtceu.api.data.worldgen.BiomeWeightModifier;
import com.gregtechceu.gtceu.api.data.worldgen.modifier.BiomePlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = BiomePlacement.class, remap = false)
public abstract class GregTech$BiomePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Final
    @Shadow
    public List<BiomeWeightModifier> modifiers;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        BlockPos pos = BlockPos.of(packedPos);

        for (BiomeWeightModifier modifier : this.modifiers) {
            if (modifier.addedWeight < 100
                    && random.nextInt(100) >= modifier.addedWeight
                    && modifier.biomes.get().contains(context.getLevel().getBiome(pos))) {
                return;
            }
        }

        output.add(packedPos);
    }
}
