package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Thread-local, exact cache for structure-time base-height/base-column probes.
 *
 * <p>Keys stay primitive where possible, with object reference comparisons for
 * generator/random/settings identities so identity hash collisions cannot
 * produce false hits.
 */
public final class StructureNoiseColumnCache implements AutoCloseable {
    public static final int MISS = Integer.MIN_VALUE;

    private static final int HEIGHT_SIZE = 4096;
    private static final int HEIGHT_MASK = HEIGHT_SIZE - 1;
    private static final int COLUMN_SIZE = 128;
    private static final int COLUMN_MASK = COLUMN_SIZE - 1;

    private static final boolean ENABLED = Boolean.getBoolean("ga.structureNoiseColumnCache.enabled");
    private static final ThreadLocal<StructureNoiseColumnCache> LOCAL = new ThreadLocal<>();

    public static volatile boolean METRICS_ENABLED = Boolean.getBoolean("ga.structureNoiseColumnCache.metrics");

    private static final LongAdder SCOPES = new LongAdder();
    private static final LongAdder HEIGHT_HITS = new LongAdder();
    private static final LongAdder HEIGHT_MISSES = new LongAdder();
    private static final LongAdder HEIGHT_STORES = new LongAdder();
    private static final LongAdder HEIGHT_COLUMN_HITS = new LongAdder();
    private static final LongAdder COLUMN_HITS = new LongAdder();
    private static final LongAdder COLUMN_MISSES = new LongAdder();
    private static final LongAdder COLUMN_STORES = new LongAdder();

    private final int[] heightEpochs = new int[HEIGHT_SIZE];
    private final Object[] heightGenerators = new Object[HEIGHT_SIZE];
    private final Object[] heightRandomStates = new Object[HEIGHT_SIZE];
    private final Object[] heightSettings = new Object[HEIGHT_SIZE];
    private final long[] heightPositions = new long[HEIGHT_SIZE];
    private final long[] heightAccessorKeys = new long[HEIGHT_SIZE];
    private final long[] heightNoiseKeys = new long[HEIGHT_SIZE];
    private final long[] heightCellKeys = new long[HEIGHT_SIZE];
    private final int[] heightTypes = new int[HEIGHT_SIZE];
    private final int[] heightValues = new int[HEIGHT_SIZE];

    private final int[] columnEpochs = new int[COLUMN_SIZE];
    private final Object[] columnGenerators = new Object[COLUMN_SIZE];
    private final Object[] columnRandomStates = new Object[COLUMN_SIZE];
    private final Object[] columnSettings = new Object[COLUMN_SIZE];
    private final long[] columnPositions = new long[COLUMN_SIZE];
    private final long[] columnAccessorKeys = new long[COLUMN_SIZE];
    private final long[] columnNoiseKeys = new long[COLUMN_SIZE];
    private final long[] columnCellKeys = new long[COLUMN_SIZE];
    private final BlockState[][] columnStates = new BlockState[COLUMN_SIZE][];

    private int depth;
    private int epoch = 1;

    private StructureNoiseColumnCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static StructureNoiseColumnCache enter() {
        if (!ENABLED) {
            return null;
        }

        StructureNoiseColumnCache cache = LOCAL.get();
        if (cache == null) {
            cache = new StructureNoiseColumnCache();
            LOCAL.set(cache);
        }
        if (cache.depth++ == 0) {
            cache.advanceEpoch();
            increment(SCOPES);
        }
        return cache;
    }

    public static StructureNoiseColumnCache current() {
        if (!ENABLED) {
            return null;
        }
        StructureNoiseColumnCache cache = LOCAL.get();
        return cache != null && cache.depth > 0 ? cache : null;
    }

    public static void setMetricsEnabled(boolean enabled) {
        METRICS_ENABLED = enabled;
    }

    public static void resetMetrics() {
        SCOPES.reset();
        HEIGHT_HITS.reset();
        HEIGHT_MISSES.reset();
        HEIGHT_STORES.reset();
        HEIGHT_COLUMN_HITS.reset();
        COLUMN_HITS.reset();
        COLUMN_MISSES.reset();
        COLUMN_STORES.reset();
    }

    public static Snapshot snapshot() {
        long heightHits = HEIGHT_HITS.sum();
        long heightMisses = HEIGHT_MISSES.sum();
        long columnHits = COLUMN_HITS.sum();
        long columnMisses = COLUMN_MISSES.sum();
        return new Snapshot(
                ENABLED,
                METRICS_ENABLED,
                SCOPES.sum(),
                heightHits,
                heightMisses,
                HEIGHT_STORES.sum(),
                HEIGHT_COLUMN_HITS.sum(),
                ratio(heightHits, heightHits + heightMisses),
                columnHits,
                columnMisses,
                COLUMN_STORES.sum(),
                ratio(columnHits, columnHits + columnMisses),
                summary()
        );
    }

    public static String summary() {
        long heightHits = HEIGHT_HITS.sum();
        long heightMisses = HEIGHT_MISSES.sum();
        long columnHits = COLUMN_HITS.sum();
        long columnMisses = COLUMN_MISSES.sum();
        return "StructureNoiseColumnCache enabled=" + ENABLED
                + ", metrics=" + METRICS_ENABLED
                + ", scopes=" + SCOPES.sum()
                + ", heightHits=" + heightHits
                + ", heightMisses=" + heightMisses
                + ", heightHitRate=" + ratio(heightHits, heightHits + heightMisses)
                + ", heightColumnHits=" + HEIGHT_COLUMN_HITS.sum()
                + ", columnHits=" + columnHits
                + ", columnMisses=" + columnMisses
                + ", columnHitRate=" + ratio(columnHits, columnHits + columnMisses);
    }

    @Override
    public void close() {
        if (this.depth > 0) {
            this.depth--;
        }
    }

    public int getBaseHeight(
            Object generator,
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings
    ) {
        long position = pack(x, z);
        long accessorKey = pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
        long noiseKey = pack(noiseSettings.minY(), noiseSettings.height());
        long cellKey = pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());

        int columnIndex = columnIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey);
        BlockState[] states = matchingColumn(
                columnIndex,
                generator,
                randomState,
                generatorSettings,
                position,
                accessorKey,
                noiseKey,
                cellKey
        );
        if (states != null) {
            int value = heightFromColumn(states, noiseSettings.minY(), heightAccessor.getMinBuildHeight(), type);
            this.putBaseHeight(
                    generator,
                    x,
                    z,
                    type,
                    heightAccessor,
                    randomState,
                    generatorSettings,
                    noiseSettings,
                    value
            );
            increment(HEIGHT_HITS);
            increment(HEIGHT_COLUMN_HITS);
            return value;
        }

        int typeOrdinal = type.ordinal();
        int index = heightIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey, typeOrdinal);
        if (this.heightEpochs[index] == this.epoch
                && this.heightGenerators[index] == generator
                && this.heightRandomStates[index] == randomState
                && this.heightSettings[index] == generatorSettings
                && this.heightPositions[index] == position
                && this.heightAccessorKeys[index] == accessorKey
                && this.heightNoiseKeys[index] == noiseKey
                && this.heightCellKeys[index] == cellKey
                && this.heightTypes[index] == typeOrdinal) {
            increment(HEIGHT_HITS);
            return this.heightValues[index];
        }

        increment(HEIGHT_MISSES);
        return MISS;
    }

    public void putBaseHeight(
            Object generator,
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings,
            int value
    ) {
        long position = pack(x, z);
        long accessorKey = pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
        long noiseKey = pack(noiseSettings.minY(), noiseSettings.height());
        long cellKey = pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
        int typeOrdinal = type.ordinal();
        int index = heightIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey, typeOrdinal);

        this.heightEpochs[index] = this.epoch;
        this.heightGenerators[index] = generator;
        this.heightRandomStates[index] = randomState;
        this.heightSettings[index] = generatorSettings;
        this.heightPositions[index] = position;
        this.heightAccessorKeys[index] = accessorKey;
        this.heightNoiseKeys[index] = noiseKey;
        this.heightCellKeys[index] = cellKey;
        this.heightTypes[index] = typeOrdinal;
        this.heightValues[index] = value;
        increment(HEIGHT_STORES);
    }

    public NoiseColumn getBaseColumn(
            Object generator,
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings
    ) {
        long position = pack(x, z);
        long accessorKey = pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
        long noiseKey = pack(noiseSettings.minY(), noiseSettings.height());
        long cellKey = pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
        int index = columnIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey);
        BlockState[] states = matchingColumn(
                index,
                generator,
                randomState,
                generatorSettings,
                position,
                accessorKey,
                noiseKey,
                cellKey
        );
        if (states == null) {
            increment(COLUMN_MISSES);
            return null;
        }

        increment(COLUMN_HITS);
        return new NoiseColumn(noiseSettings.minY(), states.clone());
    }

    public boolean hasBaseColumn(
            Object generator,
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings
    ) {
        long position = pack(x, z);
        long accessorKey = pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
        long noiseKey = pack(noiseSettings.minY(), noiseSettings.height());
        long cellKey = pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
        int index = columnIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey);
        return matchingColumn(
                index,
                generator,
                randomState,
                generatorSettings,
                position,
                accessorKey,
                noiseKey,
                cellKey
        ) != null;
    }

    public void putBaseColumn(
            Object generator,
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings,
            NoiseColumn column
    ) {
        int height = noiseSettings.height();
        BlockState[] states = new BlockState[height];
        int minY = noiseSettings.minY();
        for (int i = 0; i < height; i++) {
            states[i] = column.getBlock(minY + i);
        }
        this.storeBaseColumn(generator, x, z, heightAccessor, randomState, generatorSettings, noiseSettings, states);
    }

    public void putBaseColumn(
            Object generator,
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings,
            BlockState[] states
    ) {
        if (states.length != noiseSettings.height()) {
            throw new IllegalArgumentException("Column length " + states.length + " != noise height " + noiseSettings.height());
        }
        this.storeBaseColumn(
                generator,
                x,
                z,
                heightAccessor,
                randomState,
                generatorSettings,
                noiseSettings,
                Arrays.copyOf(states, noiseSettings.height())
        );
    }

    private void storeBaseColumn(
            Object generator,
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            Object randomState,
            Object generatorSettings,
            NoiseSettings noiseSettings,
            BlockState[] states
    ) {
        long position = pack(x, z);
        long accessorKey = pack(heightAccessor.getMinBuildHeight(), heightAccessor.getHeight());
        long noiseKey = pack(noiseSettings.minY(), noiseSettings.height());
        long cellKey = pack(noiseSettings.getCellWidth(), noiseSettings.getCellHeight());
        int index = columnIndex(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey);

        this.columnEpochs[index] = this.epoch;
        this.columnGenerators[index] = generator;
        this.columnRandomStates[index] = randomState;
        this.columnSettings[index] = generatorSettings;
        this.columnPositions[index] = position;
        this.columnAccessorKeys[index] = accessorKey;
        this.columnNoiseKeys[index] = noiseKey;
        this.columnCellKeys[index] = cellKey;
        this.columnStates[index] = states;
        increment(COLUMN_STORES);
    }

    private BlockState[] matchingColumn(
            int index,
            Object generator,
            Object randomState,
            Object generatorSettings,
            long position,
            long accessorKey,
            long noiseKey,
            long cellKey
    ) {
        if (this.columnEpochs[index] == this.epoch
                && this.columnGenerators[index] == generator
                && this.columnRandomStates[index] == randomState
                && this.columnSettings[index] == generatorSettings
                && this.columnPositions[index] == position
                && this.columnAccessorKeys[index] == accessorKey
                && this.columnNoiseKeys[index] == noiseKey
                && this.columnCellKeys[index] == cellKey) {
            return this.columnStates[index];
        }
        return null;
    }

    private void advanceEpoch() {
        this.epoch++;
        if (this.epoch == 0) {
            Arrays.fill(this.heightEpochs, 0);
            Arrays.fill(this.columnEpochs, 0);
            this.epoch = 1;
        }
    }

    private static int heightFromColumn(
            BlockState[] states,
            int minY,
            int fallbackMinBuildHeight,
            Heightmap.Types type
    ) {
        Predicate<BlockState> opaque = type.isOpaque();
        for (int i = states.length - 1; i >= 0; i--) {
            BlockState state = states[i];
            if (state != null && opaque.test(state)) {
                return minY + i + 1;
            }
        }
        return fallbackMinBuildHeight;
    }

    private static long pack(int high, int low) {
        return ((long) high << 32) ^ (low & 0xFFFF_FFFFL);
    }

    private static int heightIndex(
            Object generator,
            Object randomState,
            Object generatorSettings,
            long position,
            long accessorKey,
            long noiseKey,
            long cellKey,
            int typeOrdinal
    ) {
        long h = baseHash(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey);
        h ^= Integer.toUnsignedLong(typeOrdinal) * 0x9E3779B97F4A7C15L;
        return (int) mix(h) & HEIGHT_MASK;
    }

    private static int columnIndex(
            Object generator,
            Object randomState,
            Object generatorSettings,
            long position,
            long accessorKey,
            long noiseKey,
            long cellKey
    ) {
        return (int) mix(baseHash(generator, randomState, generatorSettings, position, accessorKey, noiseKey, cellKey))
                & COLUMN_MASK;
    }

    private static long baseHash(
            Object generator,
            Object randomState,
            Object generatorSettings,
            long position,
            long accessorKey,
            long noiseKey,
            long cellKey
    ) {
        long h = position;
        h ^= Long.rotateLeft(accessorKey * 0x9E3779B97F4A7C15L, 17);
        h ^= Long.rotateLeft(noiseKey * 0xC2B2AE3D27D4EB4FL, 31);
        h ^= Long.rotateLeft(cellKey * 0x165667B19E3779F9L, 47);
        h ^= (long) System.identityHashCode(generator) * 0x85EBCA77C2B2AE63L;
        h ^= (long) System.identityHashCode(randomState) * 0x27D4EB2F165667C5L;
        h ^= (long) System.identityHashCode(generatorSettings) * 0x94D049BB133111EBL;
        return h;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    private static void increment(LongAdder counter) {
        if (METRICS_ENABLED) {
            counter.increment();
        }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0D : (double) numerator / (double) denominator;
    }

    public record Snapshot(
            boolean enabled,
            boolean metricsEnabled,
            long scopes,
            long heightHits,
            long heightMisses,
            long heightStores,
            long heightColumnHits,
            double heightHitRate,
            long columnHits,
            long columnMisses,
            long columnStores,
            double columnHitRate,
            String summary
    ) {
    }
}
