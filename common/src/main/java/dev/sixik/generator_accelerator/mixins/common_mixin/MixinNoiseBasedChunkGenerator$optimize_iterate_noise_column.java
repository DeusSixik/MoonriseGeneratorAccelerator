package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 *
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator$optimize_iterate_noise_column {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    @Final
    private Supplier<Aquifer.FluidPicker> globalFluidPicker;

    @Unique
    private static final ThreadLocal<BaseHeightCache> BTS$BASE_HEIGHT_CACHE =
            ThreadLocal.withInitial(BaseHeightCache::new);

    /**
     * @author Sixik
     * @reason Completely abandoning OptionalInt. Using a fast processor branch.
     */
    @Overwrite
    public int getBaseHeight(int x, int z, Heightmap.Types types, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        long key1 = ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
        long key2 = bts$baseHeightMetaKey(types, levelHeightAccessor, randomState, generatorSettings, noiseSettings);
        BaseHeightCache cache = BTS$BASE_HEIGHT_CACHE.get();
        int cached = cache.get(key1, key2);
        if (cached != BaseHeightCache.MISS) {
            return cached;
        }

        final int resultY = this.bts$iterateNoiseColumn(levelHeightAccessor, randomState, x, z, null, types.isOpaque());
        int result = resultY == Integer.MIN_VALUE ? levelHeightAccessor.getMinBuildHeight() : resultY;
        cache.put(key1, key2, result);
        return result;
    }

    /**
     * @author Sixik
     * @reason Destroying a MutableObject. Passing an array directly to fill it.
     */
    @Overwrite
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        BlockState[] states = new BlockState[noiseSettings.height()];
        this.bts$iterateNoiseColumn(levelHeightAccessor, randomState, x, z, states, null);
        return new NoiseColumn(noiseSettings.minY(), states);
    }

    @Unique
    private int bts$iterateNoiseColumn(
            LevelHeightAccessor levelHeightAccessor, RandomState randomState, int i, int j,
            BlockState[] outStates, @Nullable Predicate<BlockState> predicate
    ) {
        NoiseGeneratorSettings settings = this.settings.value();
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);

        int cellHeight = noiseSettings.getCellHeight();
        int shiftH = Integer.numberOfTrailingZeros(cellHeight);
        int n = noiseSettings.height() >> shiftH;

        if (n <= 0) {
            return Integer.MIN_VALUE;
        }

        int cellWidth = noiseSettings.getCellWidth();
        int shiftW = Integer.numberOfTrailingZeros(cellWidth);
        int maskW = cellWidth - 1;

        int l = noiseSettings.minY();
        int m = l >> shiftH;

        int p = i >> shiftW;
        int q = j >> shiftW;
        int r = i & maskW;
        int s = j & maskW;

        int t = p << shiftW;
        int u = q << shiftW;
        double d = (double) r / (double) cellWidth;
        double e = (double) s / (double) cellWidth;

        NoiseChunk noiseChunk = new NoiseChunk(
                1, randomState, t, u, noiseSettings,
                DensityFunctions.BeardifierMarker.INSTANCE,
                settings, this.globalFluidPicker.get(), Blender.empty()
        );

        noiseChunk.initializeForFirstCellX();
        noiseChunk.advanceCellX(0);

        BlockState defaultState = settings.defaultBlock();

        for (int v = n - 1; v >= 0; --v) {
            noiseChunk.selectCellYZ(v, 0);
            for (int w = cellHeight - 1; w >= 0; --w) {
                int x = (m + v) * cellHeight + w;
                double f = (double) w / (double) cellHeight;

                noiseChunk.updateForY(x, f);
                noiseChunk.updateForX(i, d);
                noiseChunk.updateForZ(j, e);

                BlockState blockState = noiseChunk.getInterpolatedState();
                if (blockState == null) {
                    blockState = defaultState;
                }

                if (outStates != null) {
                    outStates[v * cellHeight + w] = blockState;
                }

                if (predicate != null && predicate.test(blockState)) {
                    noiseChunk.stopInterpolation();
                    return x + 1;
                }
            }
        }
        noiseChunk.stopInterpolation();
        return Integer.MIN_VALUE;
    }

    @Unique
    private static long bts$baseHeightMetaKey(
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            RandomState randomState,
            NoiseGeneratorSettings generatorSettings,
            NoiseSettings noiseSettings
    ) {
        int meta = type.ordinal();
        meta = 31 * meta + heightAccessor.getMinBuildHeight();
        meta = 31 * meta + heightAccessor.getHeight();
        meta = 31 * meta + noiseSettings.minY();
        meta = 31 * meta + noiseSettings.height();
        meta = 31 * meta + System.identityHashCode(randomState);
        meta = 31 * meta + System.identityHashCode(generatorSettings);
        return ((long) meta << 32) ^ (type.ordinal() & 0xFFFF_FFFFL);
    }

    @Unique
    private static final class BaseHeightCache {
        private static final int MISS = Integer.MIN_VALUE;
        private static final int SIZE = 16_384;
        private static final int MASK = SIZE - 1;

        private final long[] keys1 = new long[SIZE];
        private final long[] keys2 = new long[SIZE];
        private final int[] values = new int[SIZE];

        private BaseHeightCache() {
            Arrays.fill(this.values, MISS);
        }

        private int get(long key1, long key2) {
            int index = index(key1, key2);
            if (this.values[index] != MISS && this.keys1[index] == key1 && this.keys2[index] == key2) {
                return this.values[index];
            }
            return MISS;
        }

        private void put(long key1, long key2, int value) {
            int index = index(key1, key2);
            this.keys1[index] = key1;
            this.keys2[index] = key2;
            this.values[index] = value;
        }

        private static int index(long key1, long key2) {
            long h = key1 * 0x9E3779B97F4A7C15L;
            h ^= Long.rotateLeft(key2 * 0xC2B2AE3D27D4EB4FL, 31);
            h ^= h >>> 33;
            return (int) h & MASK;
        }
    }
}
