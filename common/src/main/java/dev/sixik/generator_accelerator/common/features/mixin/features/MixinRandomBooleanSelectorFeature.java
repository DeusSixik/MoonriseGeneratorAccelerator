package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.IdentityHashMap;

@Mixin(value = RandomBooleanSelectorFeature.class, priority = 999)
public abstract class MixinRandomBooleanSelectorFeature extends Feature<RandomBooleanFeatureConfiguration> {

    @Unique
    private static final ThreadLocal<IdentityHashMap<RandomBooleanFeatureConfiguration, CompiledRandomBooleanSelector>> GA$CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private MixinRandomBooleanSelectorFeature(Codec<RandomBooleanFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Cache holder unwraps and avoid extra branches/allocations in the vanilla selector path.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<RandomBooleanFeatureConfiguration> placeContext) {
        RandomBooleanFeatureConfiguration config = placeContext.config();
        CompiledRandomBooleanSelector compiled = GA$CACHE.get().computeIfAbsent(
                config,
                key -> new CompiledRandomBooleanSelector(key.featureTrue.value(), key.featureFalse.value())
        );

        RandomSource random = placeContext.random();
        WorldGenLevel level = placeContext.level();
        ChunkGenerator generator = placeContext.chunkGenerator();
        BlockPos origin = placeContext.origin();
        return (random.nextBoolean() ? compiled.featureTrue() : compiled.featureFalse()).place(level, generator, random, origin);
    }

    @Unique
    private record CompiledRandomBooleanSelector(PlacedFeature featureTrue, PlacedFeature featureFalse) {
    }
}
