package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.IdentityHashMap;

@Mixin(value = SimpleRandomSelectorFeature.class, priority = 999)
public abstract class MixinSimpleRandomSelectorFeature extends Feature<SimpleRandomFeatureConfiguration> {

    @Unique
    private static final ThreadLocal<IdentityHashMap<SimpleRandomFeatureConfiguration, PlacedFeature[]>> GA$CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private MixinSimpleRandomSelectorFeature(Codec<SimpleRandomFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Cache flattened placed-feature arrays for the hot random selector path.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<SimpleRandomFeatureConfiguration> placeContext) {
        SimpleRandomFeatureConfiguration config = placeContext.config();
        PlacedFeature[] features = GA$CACHE.get().computeIfAbsent(config, MixinSimpleRandomSelectorFeature::ga$compile);
        RandomSource random = placeContext.random();
        WorldGenLevel level = placeContext.level();
        ChunkGenerator generator = placeContext.chunkGenerator();
        BlockPos origin = placeContext.origin();
        return features[random.nextInt(features.length)].place(level, generator, random, origin);
    }

    @Unique
    private static PlacedFeature[] ga$compile(SimpleRandomFeatureConfiguration config) {
        HolderSet<PlacedFeature> holderSet = config.features;
        int size = holderSet.size();
        PlacedFeature[] features = new PlacedFeature[size];
        for (int i = 0; i < size; i++) {
            Holder<PlacedFeature> holder = holderSet.get(i);
            features[i] = holder.value();
        }
        return features;
    }
}
