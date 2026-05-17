package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BiomeSourceExtern;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.*;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mixin(BiomeSource.class)
public abstract class MixinBiomeSource implements GA$BiomeSourceExtern {

    @Shadow
    protected abstract Stream<Holder<Biome>> collectPossibleBiomes();

    @Unique
    private Set<Holder<Biome>> ga$caced_biomes = null;

    @Override
    public Set<Holder<Biome>> ga$getCache() {
        if(ga$caced_biomes == null) {
            ga$caced_biomes = this.collectPossibleBiomes().collect(Collectors.toCollection(ObjectOpenHashSet::new));
        }

        return ga$caced_biomes;
    }

    @Override
    public Set<Holder<Biome>> ga$getCacheNotNull() {
        return ga$caced_biomes == null ? this.ga$getCacheCallback() : ga$caced_biomes;
    }

    @Unique
    public Set<Holder<Biome>> ga$getCacheCallback() {
        return this.collectPossibleBiomes().collect(Collectors.toCollection(ObjectOpenHashSet::new));
    }

    @Override
    public void ga$setCache(Set<Holder<Biome>> biomes) {
        this.ga$caced_biomes = biomes;
    }
}
