package dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$SubBiomeRequest", remap = false)
public interface Biolith$SubBiomeRequestAccessor {
    @Accessor("criterion")
    Criterion ga$getCriterion();
}
