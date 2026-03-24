package dev.sixik.generator_accelerator.common.surface;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.function.Predicate;

public class SurfaceBiomeCondition extends SurfaceRules.LazyYCondition {

    protected final Predicate<ResourceKey<Biome>> biomeNameTeg;

    public SurfaceBiomeCondition(SurfaceRules.Context context, SurfaceRules.BiomeConditionSource source) {
        super(context);
        this.biomeNameTeg = source.biomeNameTest;
    }

    @Override
    protected boolean compute() {
        final Holder<Biome> biome = ((SurfaceRulesContextBiomeGetter)(Object)this.context).bts$getBiomeHolderCached();
        return biome.is(biomeNameTeg);
    }
}
