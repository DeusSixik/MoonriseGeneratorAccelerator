package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.gregtech;

import com.gregtechceu.gtceu.api.data.worldgen.modifier.DimensionFilter;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = DimensionFilter.class, remap = false)
public abstract class GregTech$DimensionFilterMixin extends PlacementFilter implements GA$PlacementModifierExtension {

    @Shadow
    public HolderSet<DimensionType> dimensionId;

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        if (this.dimensionId.contains(context.getLevel().getLevel().dimensionTypeRegistration())) {
            output.add(packedPos);
        }
    }
}
