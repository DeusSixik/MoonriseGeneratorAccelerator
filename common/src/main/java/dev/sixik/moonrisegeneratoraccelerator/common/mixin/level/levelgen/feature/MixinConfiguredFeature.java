package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.feature;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.features.PooledFeatureContext;
import dev.sixik.moonrisegeneratoraccelerator.common.utils.pools.SimpleObjectPool;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ConfiguredFeature.class)
public class MixinConfiguredFeature<FC extends FeatureConfiguration, F extends Feature<FC>> {


    @Shadow
    @Final
    private FC config;

    @Shadow
    @Final
    private F feature;

    /**
     * @author <a href="https://github.com/RelativityMC/C2ME-fabric/blob/dev/1.21.10/c2me-opts-allocs/src/main/java/com/ishland/c2me/opts/allocs/mixin/object_pooling_caching/MixinConfiguredFeature.java">Author ishland</a>
     * @reason
     */
    @Overwrite
    public boolean place(WorldGenLevel reader, ChunkGenerator chunkGenerator, RandomSource random, BlockPos pos) {
        if(!reader.ensureCanWrite(pos)) return false;

        final SimpleObjectPool<PooledFeatureContext<?>> pool = PooledFeatureContext.POOL.get();
        final PooledFeatureContext<FC> context = (PooledFeatureContext<FC>) pool.alloc();

        try {
            context.reInit(null, reader, chunkGenerator, random, pos, config);
            return feature.place(context);
        } finally {
            pool.release(context);
        }
    }
}
