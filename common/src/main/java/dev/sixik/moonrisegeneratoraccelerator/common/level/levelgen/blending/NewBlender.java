package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.blending;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An optimized implementation of terrain blending that uses flat arrays for faster data access.
 *
 * <p>This class extends the standard {@link Blender} but stores blending data in linear arrays
 * instead of hash maps for performance-critical operations. It's designed to work with
 * Minecraft's chunk generation system, providing smooth transitions between terrain.</p>
 *
 * <p>Key performance optimizations include:
 * <ul>
 *   <li>Storing keys and values in parallel arrays for cache-friendly iteration</li>
 *   <li>Using linear search for small datasets (typically < 50 entries) instead of hash lookups</li>
 *   <li>Using distance-squared comparisons to avoid expensive square root calculations</li>
 * </ul></p>
 *
 * <p>The class provides three main blending operations:
 * <ol>
 *   <li>Height blending for terrain surfaces</li>
 *   <li>Density blending for volumetric terrain generation</li>
 *   <li>Biome blending for smooth biome transitions</li>
 * </ol></p>
 */
public class NewBlender extends Blender {

    /**
     * An empty blender instance that performs no blending operations.
     *
     * <p>This instance returns original values unchanged and is used when:
     * <ul>
     *   <li>No neighboring chunks are available for blending</li>
     *   <li>World generation region doesn't support blending</li>
     *   <li>Explicitly requesting no blending operations</li>
     * </ul></p>
     */
    public static final Blender EMPTY = new NewBlender(new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>()){

        @Override
        public @NotNull BlendingOutput blendOffsetAndFactor(int i, int j) {
            return new BlendingOutput(1.0, 0.0);
        }

        @Override
        public double blendDensity(DensityFunction.FunctionContext functionContext, double d) {
            return d;
        }

        @Override
        public BiomeResolver getBiomeResolver(BiomeResolver biomeResolver) {
            return biomeResolver;
        }
    };

    private static final double HEIGHT_BLENDING_RANGE_CELLS_SQ = HEIGHT_BLENDING_RANGE_CELLS * HEIGHT_BLENDING_RANGE_CELLS;
    private static final double DENSITY_BLENDING_RANGE_CELLS_SQ = 4.0D;

    private final long[] heightKeys;
    private final BlendingData[] heightValues;
    private final int heightCount;

    private final long[] densityKeys;
    private final BlendingData[] densityValues;
    private final int densityCount;

    /**
     * Creates an empty blender instance.
     *
     * @return An empty blender that performs no blending operations
     */
    public static Blender emptyNew() {
        return EMPTY;
    }

    /**
     * Creates a blender instance for the specified world generation region.
     *
     * <p>This method collects blending data from neighboring chunks within the
     * blending range and creates an optimized blender instance. If no suitable
     * neighboring chunks exist or the region doesn't support blending, an
     * empty blender is returned.</p>
     *
     * @param worldGenRegion The world generation region to create a blender for
     * @return An optimized blender instance or {@link #EMPTY} if blending is not possible
     */
    public static @NotNull Blender ofNew(@Nullable WorldGenRegion worldGenRegion) {
        if (worldGenRegion == null) {
            return EMPTY;
        }
        final ChunkPos chunkPos = worldGenRegion.getCenter();
        if (!worldGenRegion.isOldChunkAround(chunkPos, HEIGHT_BLENDING_RANGE_CHUNKS)) {
            return EMPTY;
        }

        final Long2ObjectOpenHashMap<BlendingData> heightMap = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<BlendingData> densityMap = new Long2ObjectOpenHashMap<>();

        final int rangeSq = Mth.square(HEIGHT_BLENDING_RANGE_CHUNKS + 1);

        for(int j = -HEIGHT_BLENDING_RANGE_CHUNKS; j <= HEIGHT_BLENDING_RANGE_CHUNKS; ++j) {
            for(int k = -HEIGHT_BLENDING_RANGE_CHUNKS; k <= HEIGHT_BLENDING_RANGE_CHUNKS; ++k) {
                if (j * j + k * k <= rangeSq) {
                    final int x = chunkPos.x + j;
                    final int z = chunkPos.z + k;
                    final BlendingData data = BlendingData.getOrUpdateBlendingData(worldGenRegion, x, z);
                    if (data != null) {
                        final long key = ChunkPos.asLong(x, z);
                        heightMap.put(key, data);
                        if (j >= -DENSITY_BLENDING_RANGE_CHUNKS && j <= DENSITY_BLENDING_RANGE_CHUNKS &&
                                k >= -DENSITY_BLENDING_RANGE_CHUNKS && k <= DENSITY_BLENDING_RANGE_CHUNKS) {
                            densityMap.put(key, data);
                        }
                    }
                }
            }
        }

        if (heightMap.isEmpty() && densityMap.isEmpty()) {
            return EMPTY;
        }
        return new NewBlender(heightMap, densityMap);
    }

    /**
     * Constructs a new optimized blender with pre-collected blending data.
     *
     * <p>This constructor converts hash maps into parallel arrays for faster
     * iteration during blending operations. The data is copied to ensure
     * thread safety and consistent performance.</p>
     *
     * @param heightMap Mapping from chunk coordinates to height blending data
     * @param densityMap Mapping from chunk coordinates to density blending data
     */
    public NewBlender(Long2ObjectOpenHashMap<BlendingData> heightMap, Long2ObjectOpenHashMap<BlendingData> densityMap) {
        super(heightMap, densityMap);
        this.heightCount = heightMap.size();
        this.heightKeys = new long[this.heightCount];
        this.heightValues = new BlendingData[this.heightCount];

        int i = 0;
        for(Long2ObjectMap.Entry<BlendingData> entry : heightMap.long2ObjectEntrySet()) {
            this.heightKeys[i] = entry.getLongKey();
            this.heightValues[i] = entry.getValue();
            i++;
        }

        this.densityCount = densityMap.size();
        this.densityKeys = new long[this.densityCount];
        this.densityValues = new BlendingData[this.densityCount];

        i = 0;
        for(Long2ObjectMap.Entry<BlendingData> entry : densityMap.long2ObjectEntrySet()) {
            this.densityKeys[i] = entry.getLongKey();
            this.densityValues[i] = entry.getValue();
            i++;
        }
    }

    /**
     * Blends height offset and factor for the specified coordinates.
     *
     * <p>This method implements inverse distance weighting (IDW) interpolation
     * using a 1/d⁴ weighting function. It first attempts to find an exact
     * height value at the query coordinates, falling back to weighted
     * interpolation from neighboring data points.</p>
     *
     * @param x The block X coordinate
     * @param z The block Z coordinate
     * @return A {@link BlendingOutput} containing the blend factor and height offset
     */
    @Override
    public @NotNull BlendingOutput blendOffsetAndFactor(int x, int z) {
        final int quartX = x >> 2;
        final int quartZ = z >> 2;

        final double exactHeight = this.queryHeight(quartX, quartZ);
        if (exactHeight != Double.MAX_VALUE) {
            return new BlendingOutput(0.0, Blender.heightToOffset(exactHeight));
        }

        /*
            accumulator: [0]=weightedSum, [1]=totalWeight, [2]=minDistSq
         */
        final double[] acc = new double[] { 0.0, 0.0, Double.POSITIVE_INFINITY };

        final int count = this.heightCount;
        final long[] keys = this.heightKeys;
        final BlendingData[] values = this.heightValues;

        for(int i = 0; i < count; ++i) {
            final long key = keys[i];
            final BlendingData data = values[i];

            /*
                We use fast bit operations to extract coordinates.
             */
            final int chunkX = (int)key;
            final int chunkZ = (int)(key >>> 32);

            final int secX = QuartPos.fromSection(chunkX);
            final int secZ = QuartPos.fromSection(chunkZ);

            // TODO: Remove iterator and use array from data
            data.iterateHeights(secX, secZ, (targetX, targetZ, height) -> {
                final double dx = quartX - targetX;
                final double dz = quartZ - targetZ;

                /*
                    Math Optimization: Distance Squared
                 */
                final double distSq = dx * dx + dz * dz;

                if (distSq <= HEIGHT_BLENDING_RANGE_CELLS_SQ) {
                    if (distSq < acc[2]) {
                        acc[2] = distSq;
                    }

                    /*
                        1 / d^4 == 1 / (d^2 * d^2)
                     */
                    final double weight = 1.0 / (distSq * distSq);

                    acc[0] += height * weight;
                    acc[1] += weight;
                }
            });
        }

        if (acc[2] == Double.POSITIVE_INFINITY) {
            return new BlendingOutput(1.0, 0.0);
        } else {
            final double offset = acc[0] / acc[1];
            final double minDist = Math.sqrt(acc[2]);
            double f = Mth.clamp(minDist / (double)(HEIGHT_BLENDING_RANGE_CELLS + 1), 0.0, 1.0);
            f = 3.0 * f * f - 2.0 * f * f * f;
            return new BlendingOutput(f, Blender.heightToOffset(offset));
        }
    }

    /**
     * Blends density values for terrain generation at a specific 3D location.
     *
     * <p>Similar to height blending but operates in three dimensions with
     * different blending ranges. Uses inverse distance weighting with 1/d⁴
     * weights and falls back to original density when no blending data is
     * available.</p>
     *
     * @param ctx The density function context containing position information
     * @param originalDensity The original density value before blending
     * @return The blended density value
     */
    @Override
    public double blendDensity(DensityFunction.FunctionContext ctx, double originalDensity) {
        final int quartX = ctx.blockX() >> 2;
        final int quartY = ctx.blockY() / 8;
        final int quartZ = ctx.blockZ() >> 2;

        final double exactDensity = this.queryDensity(quartX, quartY, quartZ);
        if (exactDensity != Double.MAX_VALUE) {
            return exactDensity;
        }

        /*
            accumulator: [0]=weightedSum, [1]=totalWeight, [2]=minDistSq
         */
        final double[] acc = new double[] { 0.0, 0.0, Double.POSITIVE_INFINITY };

        final int count = this.densityCount;
        final long[] keys = this.densityKeys;
        final BlendingData[] values = this.densityValues;

        for(int i = 0; i < count; ++i) {
            final long key = keys[i];
            final BlendingData data = values[i];

            final int chunkX = (int)key;
            final int chunkZ = (int)(key >>> 32);
            final int secX = QuartPos.fromSection(chunkX);
            final int secZ = QuartPos.fromSection(chunkZ);

            // TODO: Remove iterator and use array from data
            data.iterateDensities(secX, secZ, quartY - 1, quartY + 1, (targetX, targetY, targetZ, density) -> {
                final double dx = quartX - targetX;
                final double dy = (quartY - targetY) * 2;
                final double dz = quartZ - targetZ;

                final double distSq = dx * dx + dy * dy + dz * dz;

                if (distSq <= DENSITY_BLENDING_RANGE_CELLS_SQ) {
                    if (distSq < acc[2]) {
                        acc[2] = distSq;
                    }

                    final double weight = 1.0 / (distSq * distSq);
                    acc[0] += density * weight;
                    acc[1] += weight;
                }
            });
        }

        if (acc[2] == Double.POSITIVE_INFINITY) {
            return originalDensity;
        } else {
            final double blended = acc[0] / acc[1];
            final double minDist = Math.sqrt(acc[2]);
            final double factor = Mth.clamp(minDist / 3.0, 0.0, 1.0);
            return Mth.lerp(factor, blended, originalDensity);
        }
    }

    /**
     * Creates a biome resolver that blends biome information from neighboring chunks.
     *
     * <p>The returned resolver uses nearest-neighbor interpolation with distance
     * weighting and adds random noise to create natural-looking biome transitions.
     * When far from known biome data points, it falls back to the original
     * biome resolver.</p>
     *
     * @param original The original biome resolver to fall back to
     * @return A blended biome resolver
     */
    @Override
    public BiomeResolver getBiomeResolver(BiomeResolver original) {
        return (x, y, z, sampler) -> {
            Object[] result = new Object[2]; // [0]=Holder<Biome>, [1]=Double(minDistSq)
            result[1] = Double.POSITIVE_INFINITY;

            final int quartX = x;
            final int quartZ = z;
            final int count = this.heightCount;
            final long[] keys = this.heightKeys;
            final BlendingData[] values = this.heightValues;

            for(int i = 0; i < count; ++i) {
                final long key = keys[i];
                final BlendingData data = values[i];

                final int chunkX = (int)key;
                final int chunkZ = (int)(key >>> 32);
                final int secX = QuartPos.fromSection(chunkX);
                final int secZ = QuartPos.fromSection(chunkZ);

                // TODO: Remove iterator and use array from data
                data.iterateBiomes(secX, y, secZ, (targetX, targetZ, holder) -> {
                    final double dx = quartX - targetX;
                    final double dz = quartZ - targetZ;
                    final double distSq = dx * dx + dz * dz;

                    if (distSq <= HEIGHT_BLENDING_RANGE_CELLS_SQ) {
                        final double currentMin = (double)result[1];
                        if (distSq < currentMin) {
                            result[1] = distSq;
                            result[0] = holder;
                        }
                    }
                });
            }

            final double minDistSq = (double)result[1];
            if (minDistSq == Double.POSITIVE_INFINITY) {
                return original.getNoiseBiome(x, y, z, sampler);
            } else {
                final double minDist = Math.sqrt(minDistSq);
                final double shift = SHIFT_NOISE.getValue(quartX, 0.0, quartZ) * 12.0;
                final double factor = Mth.clamp((minDist + shift) / (double)(HEIGHT_BLENDING_RANGE_CELLS + 1), 0.0, 1.0);
                return factor > 0.5 ? original.getNoiseBiome(x, y, z, sampler) : (Holder<Biome>)result[0];
            }
        };
    }

    /**
     * Queries height data at quarter-resolution coordinates.
     *
     * <p>First checks the primary chunk containing the coordinates, then
     * queries neighboring chunks if the coordinates are on chunk borders.
     * This implements vanilla Minecraft's border blending logic.</p>
     *
     * @param quartX Quarter-resolution X coordinate
     * @param quartZ Quarter-resolution Z coordinate
     * @return The height value or {@link Double#MAX_VALUE} if not found
     */
    private double queryHeight(int quartX, int quartZ) {
        /*
            We check the bitmask: are we on the
            border of the section (0, 1, 2, 3 -> 0 this is the border)
         */
        final boolean isBorderX = (quartX & 3) == 0;
        final boolean isBorderZ = (quartZ & 3) == 0;

        final int chunkX = QuartPos.toSection(quartX);
        final int chunkZ = QuartPos.toSection(quartZ);

        /*
            Trying to get the value from the main chunk
         */
        double value = this.readHeight(chunkX, chunkZ, quartX, quartZ);

        /*
            If the value is valid, we return
         */
        if (value != Double.MAX_VALUE) {
            return value;
        }

        /*
            If we are at the borders and there is no data, we check the neighbors (vanilla logic)
         */
        if (isBorderX && isBorderZ) {
            /*
                Diagonal neighbor (X-1, Z-1)
             */
            value = this.readHeight(chunkX - 1, chunkZ - 1, quartX, quartZ);
        }

        if (value == Double.MAX_VALUE) {
            if (isBorderX) {
                /*
                    The neighbor on the left (X-1)
                 */
                value = this.readHeight(chunkX - 1, chunkZ, quartX, quartZ);
            }

            if (value == Double.MAX_VALUE && isBorderZ) {
                /*
                    The neighbor from above (Z-1)
                 */
                value = this.readHeight(chunkX, chunkZ - 1, quartX, quartZ);
            }
        }

        return value;
    }

    /**
     * Queries density data at quarter-resolution coordinates.
     *
     * <p>Follows the same border-checking logic as {@link #queryHeight} but
     * for 3D density data.</p>
     *
     * @param quartX Quarter-resolution X coordinate
     * @param quartY Quarter-resolution Y coordinate
     * @param quartZ Quarter-resolution Z coordinate
     * @return The density value or {@link Double#MAX_VALUE} if not found
     */
    private double queryDensity(int quartX, int quartY, int quartZ) {
        final boolean isBorderX = (quartX & 3) == 0;
        final boolean isBorderZ = (quartZ & 3) == 0;

        final int chunkX = QuartPos.toSection(quartX);
        final int chunkZ = QuartPos.toSection(quartZ);

        double value = this.readDensity(chunkX, chunkZ, quartX, quartY, quartZ);

        if (value != Double.MAX_VALUE) {
            return value;
        }

        if (isBorderX && isBorderZ) {
            value = this.readDensity(chunkX - 1, chunkZ - 1, quartX, quartY, quartZ);
        }

        if (value == Double.MAX_VALUE) {
            if (isBorderX) {
                value = this.readDensity(chunkX - 1, chunkZ, quartX, quartY, quartZ);
            }

            if (value == Double.MAX_VALUE && isBorderZ) {
                value = this.readDensity(chunkX, chunkZ - 1, quartX, quartY, quartZ);
            }
        }

        return value;
    }


    private double readHeight(int chunkX, int chunkZ, int quartX, int quartZ) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final BlendingData data = findHeightData(key);
        if (data == null) return Double.MAX_VALUE;

        return data.getHeight(
                quartX - QuartPos.fromSection(chunkX),
                0,
                quartZ - QuartPos.fromSection(chunkZ)
        );
    }

    private double readDensity(int chunkX, int chunkZ, int quartX, int quartY, int quartZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        BlendingData data = findDensityData(key);
        if (data == null) return Double.MAX_VALUE;

        return data.getDensity(
                quartX - QuartPos.fromSection(chunkX),
                quartY,
                quartZ - QuartPos.fromSection(chunkZ)
        );
    }

    /**
     * Efficiently finds height blending data for a chunk key using linear search.
     *
     * <p>For small datasets (typically under 50 entries), linear search through
     * an array is faster than hash map lookups due to better cache locality
     * and lower overhead.</p>
     *
     * @param key The chunk key (packed X and Z coordinates)
     * @return The blending data or {@code null} if not found
     */
    private BlendingData findHeightData(long key) {
        final long[] keys = this.heightKeys;
        final int len = this.heightCount;

        for (int i = 0; i < len; i++) {
            if (keys[i] == key) {
                return this.heightValues[i];
            }
        }
        return null;
    }

    /**
     * Efficiently finds density blending data for a chunk key using linear search.
     *
     * @param key The chunk key (packed X and Z coordinates)
     * @return The blending data or {@code null} if not found
     */
    private BlendingData findDensityData(long key) {
        final long[] keys = this.densityKeys;
        final int len = this.densityCount;

        for (int i = 0; i < len; i++) {
            if (keys[i] == key) {
                return this.densityValues[i];
            }
        }
        return null;
    }

}
