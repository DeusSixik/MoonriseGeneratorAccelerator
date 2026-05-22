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
    private static final boolean BTS$BASE_HEIGHT_CACHE_ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.noiseColumn.baseHeightCache.enabled",
            "false"
    ));

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

        BaseHeightCache cache = BTS$BASE_HEIGHT_CACHE_ENABLED ? BTS$BASE_HEIGHT_CACHE.get() : null;
        if (cache != null) {
            int cached = cache.get(
                    this,
                    x,
                    z,
                    types,
                    levelHeightAccessor,
                    randomState,
                    generatorSettings,
                    noiseSettings
            );
            if (cached != BaseHeightCache.MISS) {
                return cached;
            }
        }

        final int resultY = this.bts$iterateNoiseColumn(
                levelHeightAccessor,
                randomState,
                x,
                z,
                null,
                types.isOpaque(),
                generatorSettings,
                noiseSettings
        );
        int result = resultY == Integer.MIN_VALUE ? levelHeightAccessor.getMinBuildHeight() : resultY;
        if (cache != null) {
            cache.put(
                    this,
                    x,
                    z,
                    types,
                    levelHeightAccessor,
                    randomState,
                    generatorSettings,
                    noiseSettings,
                    result
            );
        }
        return result;
    }

    /**
     * @author Sixik
     * @reason Destroying a MutableObject. Passing an array directly to fill it.
     */
    @Overwrite
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        NoiseGeneratorSettings generatorSettings = this.settings.value();
        NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        BlockState[] states = new BlockState[noiseSettings.height()];
        this.bts$iterateNoiseColumn(levelHeightAccessor, randomState, x, z, states, null, generatorSettings, noiseSettings);
        return new NoiseColumn(noiseSettings.minY(), states);
    }

    @Unique
    private int bts$iterateNoiseColumn(
            LevelHeightAccessor levelHeightAccessor, RandomState randomState, int i, int j,
            BlockState[] outStates, @Nullable Predicate<BlockState> predicate,
            NoiseGeneratorSettings settings, NoiseSettings noiseSettings
    ) {
        int cellHeight = noiseSettings.getCellHeight();
        if (cellHeight <= 0) {
            return Integer.MIN_VALUE;
        }
        int n = Math.floorDiv(noiseSettings.height(), cellHeight);

        if (n <= 0) {
            return Integer.MIN_VALUE;
        }

        int cellWidth = noiseSettings.getCellWidth();
        if (cellWidth <= 0) {
            return Integer.MIN_VALUE;
        }

        int l = noiseSettings.minY();
        int m = bts$floorDivMaybePow2(l, cellHeight);

        int p = bts$floorDivMaybePow2(i, cellWidth);
        int q = bts$floorDivMaybePow2(j, cellWidth);
        int r = bts$floorModMaybePow2(i, cellWidth);
        int s = bts$floorModMaybePow2(j, cellWidth);

        int t = p * cellWidth;
        int u = q * cellWidth;
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
    private static int bts$floorDivMaybePow2(int value, int divisor) {
        return bts$isPowerOfTwo(divisor) ? value >> Integer.numberOfTrailingZeros(divisor) : Math.floorDiv(value, divisor);
    }

    @Unique
    private static int bts$floorModMaybePow2(int value, int divisor) {
        return bts$isPowerOfTwo(divisor) ? value & (divisor - 1) : Math.floorMod(value, divisor);
    }

    @Unique
    private static boolean bts$isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    @Unique
    private static long bts$pack(int high, int low) {
        return ((long) high << 32) ^ (low & 0xFFFF_FFFFL);
    }

    @Unique
    private static long bts$mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    @Unique
    private static final class BaseHeightCache {
        private static final int MISS = Integer.MIN_VALUE;
        private static final int SIZE = 16_384;
        private static final int MASK = SIZE - 1;

        private final boolean[] valid = new boolean[SIZE];
        private final Object[] generators = new Object[SIZE];
        private final Object[] randomStates = new Object[SIZE];
        private final Object[] generatorSettings = new Object[SIZE];
        private final long[] positions = new long[SIZE];
        private final long[] accessors = new long[SIZE];
        private final long[] noiseBounds = new long[SIZE];
        private final long[] cellSizes = new long[SIZE];
        private final int[] types = new int[SIZE];
        private final int[] values = new int[SIZE];

        private BaseHeightCache() {
            Arrays.fill(this.values, MISS);
        }

        private int get(
                Object generator,
                int x,
                int z,
                Heightmap.Types type,
                LevelHeightAccessor heightAccessor,
                RandomState randomState,
                NoiseGeneratorSettings generatorSettings,
                NoiseSettings noiseSettings
        ) {
            long position = bts$pack(x, z);
            long accessor = bts$pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
            long noiseBounds = bts$pack(noiseSettings.minY(), noiseSettings.height());
            long cellSizes = bts$pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
            int typeOrdinal = type.ordinal();
            int index = index(generator, randomState, generatorSettings, position, accessor, noiseBounds, cellSizes, typeOrdinal);
            if (this.valid[index]
                    && this.generators[index] == generator
                    && this.randomStates[index] == randomState
                    && this.generatorSettings[index] == generatorSettings
                    && this.positions[index] == position
                    && this.accessors[index] == accessor
                    && this.noiseBounds[index] == noiseBounds
                    && this.cellSizes[index] == cellSizes
                    && this.types[index] == typeOrdinal) {
                return this.values[index];
            }
            return MISS;
        }

        private void put(
                Object generator,
                int x,
                int z,
                Heightmap.Types type,
                LevelHeightAccessor heightAccessor,
                RandomState randomState,
                NoiseGeneratorSettings generatorSettings,
                NoiseSettings noiseSettings,
                int value
        ) {
            long position = bts$pack(x, z);
            long accessor = bts$pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
            long noiseBounds = bts$pack(noiseSettings.minY(), noiseSettings.height());
            long cellSizes = bts$pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
            int typeOrdinal = type.ordinal();
            int index = index(generator, randomState, generatorSettings, position, accessor, noiseBounds, cellSizes, typeOrdinal);
            this.valid[index] = true;
            this.generators[index] = generator;
            this.randomStates[index] = randomState;
            this.generatorSettings[index] = generatorSettings;
            this.positions[index] = position;
            this.accessors[index] = accessor;
            this.noiseBounds[index] = noiseBounds;
            this.cellSizes[index] = cellSizes;
            this.types[index] = typeOrdinal;
            this.values[index] = value;
        }

        private static int index(
                Object generator,
                Object randomState,
                Object generatorSettings,
                long position,
                long accessor,
                long noiseBounds,
                long cellSizes,
                int typeOrdinal
        ) {
            long h = position;
            h ^= Long.rotateLeft(accessor * 0x9E3779B97F4A7C15L, 17);
            h ^= Long.rotateLeft(noiseBounds * 0xC2B2AE3D27D4EB4FL, 31);
            h ^= Long.rotateLeft(cellSizes * 0x165667B19E3779F9L, 47);
            h ^= (long) System.identityHashCode(generator) * 0x85EBCA77C2B2AE63L;
            h ^= (long) System.identityHashCode(randomState) * 0x27D4EB2F165667C5L;
            h ^= (long) System.identityHashCode(generatorSettings) * 0x94D049BB133111EBL;
            h ^= Integer.toUnsignedLong(typeOrdinal) * 0x9E3779B97F4A7C15L;
            return (int) bts$mix(h) & MASK;
        }
    }

}
