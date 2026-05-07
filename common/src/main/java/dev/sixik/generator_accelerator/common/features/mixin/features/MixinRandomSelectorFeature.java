package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.IdentityHashMap;
import java.util.List;

@Mixin(value = RandomSelectorFeature.class, priority = 999)
public abstract class MixinRandomSelectorFeature extends Feature<RandomFeatureConfiguration> {

    @Unique
    private static final ThreadLocal<IdentityHashMap<RandomFeatureConfiguration, CompiledRandomSelector>> GA$CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private MixinRandomSelectorFeature(Codec<RandomFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Flatten weighted entries into primitive arrays and avoid iterator/holder churn.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<RandomFeatureConfiguration> placeContext) {
        RandomFeatureConfiguration config = placeContext.config();
        CompiledRandomSelector compiled = GA$CACHE.get().computeIfAbsent(config, MixinRandomSelectorFeature::ga$compile);
        RandomSource random = placeContext.random();
        WorldGenLevel level = placeContext.level();
        ChunkGenerator generator = placeContext.chunkGenerator();
        BlockPos origin = placeContext.origin();

        PlacedFeature[] features = compiled.features();
        float[] chances = compiled.chances();
        for (int i = 0; i < features.length; i++) {
            if (random.nextFloat() < chances[i]) {
                return features[i].place(level, generator, random, origin);
            }
        }

        return compiled.defaultFeature().place(level, generator, random, origin);
    }

    @Unique
    private static CompiledRandomSelector ga$compile(RandomFeatureConfiguration config) {
        List<WeightedPlacedFeature> entries = config.features;
        int size = entries.size();
        PlacedFeature[] features = new PlacedFeature[size];
        float[] chances = new float[size];

        for (int i = 0; i < size; i++) {
            WeightedPlacedFeature entry = entries.get(i);
            features[i] = entry.feature.value();
            chances[i] = entry.chance;
        }

        return new CompiledRandomSelector(features, chances, config.defaultFeature.value());
    }

    @Unique
    private record CompiledRandomSelector(PlacedFeature[] features, float[] chances, PlacedFeature defaultFeature) {
    }
}
