package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.function.Predicate;

public class SurfaceRulesConditions {

    public static class BiomeCondition extends SurfaceRules.LazyYCondition {

        protected final Predicate<ResourceKey<Biome>> biomeNameTeg;

        public BiomeCondition(SurfaceRules.Context context, SurfaceRules.BiomeConditionSource source) {
            super(context);
            this.biomeNameTeg = source.biomeNameTest;
        }

        @Override
        protected boolean compute() {
            final Holder<Biome> biome = ((SurfaceRulesContextBiomeGetter)(Object)this.context).bts$getBiomeHolderCached();
            return biome.is(biomeNameTeg);
        }
    }
}
