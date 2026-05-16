package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.features.cache.SharedWeakCache;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldGenRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = BlockColumnFeature.class, priority = 999)
public abstract class MixinBlockColumnFeature extends Feature<BlockColumnConfiguration> {

    @Unique
    private static final SharedWeakCache<BlockColumnConfiguration, CompiledColumn> GA$CACHE = new SharedWeakCache<>();

    @Unique
    private static final ThreadLocal<int[]> GA$HEIGHTS =
            ThreadLocal.withInitial(() -> new int[8]);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$PLACE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$PROBE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinBlockColumnFeature(Codec<BlockColumnConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Replace list lookups and temporary positions with cached layer arrays and reusable mutable positions.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<BlockColumnConfiguration> placeContext) {
        WorldGenLevel level = placeContext.level();
        BlockColumnConfiguration config = placeContext.config();
        RandomSource random = placeContext.random();

        CompiledColumn compiled = GA$CACHE.getOrCompute(config, MixinBlockColumnFeature::ga$compile);
        BlockColumnConfiguration.Layer[] layers = compiled.layers();
        int layerCount = layers.length;
        int[] heights = ga$getHeights(layerCount);

        int totalHeight = 0;
        for (int i = 0; i < layerCount; i++) {
            int sampledHeight = layers[i].height().sample(random);
            heights[i] = sampledHeight;
            totalHeight += sampledHeight;
        }

        if (totalHeight == 0) {
            return false;
        }

        Direction direction = compiled.direction();
        BlockPos origin = placeContext.origin();
        BlockPos.MutableBlockPos placePos = GA$PLACE_POS.get();
        placePos.set(origin);
        BlockPos.MutableBlockPos probePos = GA$PROBE_POS.get();
        probePos.set(origin).move(direction);

        BlockPredicate allowedPlacement = compiled.allowedPlacement();
        for (int step = 0; step < totalHeight; step++) {
            if (!allowedPlacement.test(level, probePos)) {
                ga$truncate(heights, totalHeight, step, compiled.prioritizeTip());
                break;
            }
            probePos.move(direction);
        }

        for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
            int height = heights[layerIndex];
            if (height == 0) {
                continue;
            }

            BlockColumnConfiguration.Layer layer = layers[layerIndex];
            for (int step = 0; step < height; step++) {
                if (GAWorldGenRegionAccess.canWriteWithoutLogging(level, placePos)) {
                    level.setBlock(placePos, layer.state().getState(random, placePos), 2);
                }
                placePos.move(direction);
            }
        }

        return true;
    }

    @Unique
    private static CompiledColumn ga$compile(BlockColumnConfiguration config) {
        List<BlockColumnConfiguration.Layer> list = config.layers();
        return new CompiledColumn(
                list.toArray(new BlockColumnConfiguration.Layer[0]),
                config.direction(),
                config.allowedPlacement(),
                config.prioritizeTip()
        );
    }

    @Unique
    private static int[] ga$getHeights(int requiredLength) {
        int[] heights = GA$HEIGHTS.get();
        if (heights.length < requiredLength) {
            heights = new int[requiredLength];
            GA$HEIGHTS.set(heights);
        }
        return heights;
    }

    @Unique
    private static void ga$truncate(int[] layers, int totalHeight, int placedHeight, boolean prioritizeTip) {
        int remaining = totalHeight - placedHeight;
        int step = prioritizeTip ? 1 : -1;
        int start = prioritizeTip ? 0 : layers.length - 1;
        int end = prioritizeTip ? layers.length : -1;

        for (int index = start; index != end && remaining > 0; index += step) {
            int removed = Math.min(layers[index], remaining);
            remaining -= removed;
            layers[index] -= removed;
        }
    }

    @Unique
    private record CompiledColumn(
            BlockColumnConfiguration.Layer[] layers,
            Direction direction,
            BlockPredicate allowedPlacement,
            boolean prioritizeTip
    ) {
    }
}
