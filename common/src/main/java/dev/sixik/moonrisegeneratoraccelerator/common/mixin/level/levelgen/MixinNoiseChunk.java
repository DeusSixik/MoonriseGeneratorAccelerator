package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.CachedPointContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseChunkPatch;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.noise.NoiseChunkSliceProvider;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunk implements NoiseChunkPatch {

    @Shadow
    @Final
    List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow
    @Final
    int cellWidth;
    @Shadow
    @Final
    int cellHeight;
    @Shadow
    @Final
    List<NoiseChunk.CacheAllInCell> cellCaches;

    @Shadow
    int cellStartBlockY;
    @Shadow
    private int cellStartBlockZ;
    @Final
    @Shadow
    private int firstCellZ;
    @Final
    @Shadow
    int cellNoiseMinY;
    @Shadow
    int inCellX;
    @Shadow
    int inCellY;
    @Shadow
    int inCellZ;
    @Shadow
    long interpolationCounter;
    @Shadow
    long arrayInterpolationCounter;
    @Shadow
    boolean fillingCell;

    @Shadow
    private int cellStartBlockX;

    @Shadow
    @Final
    private NoiseSettings noiseSettings;
    @Shadow
    @Final
    private DensityFunction initialDensityNoJaggedness;
    @Shadow
    @Final
    private Long2IntMap preliminarySurfaceLevel;
    @Shadow
    private long lastBlendingDataPos;
    @Shadow
    private Blender.BlendingOutput lastBlendingOutput;
    @Shadow
    @Final
    private Blender blender;
    @Mutable
    @Shadow
    @Final
    private DensityFunction.ContextProvider sliceFillingContextProvider;
    @Shadow
    public int arrayIndex;
    @Shadow
    @Final
    public int noiseSizeXZ;
    @Shadow
    @Final
    public int firstNoiseX;
    @Shadow
    @Final
    public int firstNoiseZ;
    @Unique
    private NoiseChunk.NoiseInterpolator[] bts$interpolatorsArray;
    @Unique
    private NoiseChunk.CacheAllInCell[] bts$cellCachesArray;

    @Unique
    public double bts$inverseCellWidth;
    @Unique
    public double bts$inverseCellHeight;

    @Unique private int[] surfaceCache;

    @Unique private CachedPointContext reusableContext;

    @Override
    public double bts$getInverseCellHeight() {
        return bts$inverseCellHeight;
    }

    @Override
    public double bts$getInverseCellWidth() {
        return bts$inverseCellWidth;
    }


    @Inject(method = "<init>", at = @At("TAIL"))
    private void bts$initOptimizationFields(CallbackInfo ci) {
        this.sliceFillingContextProvider = new NoiseChunkSliceProvider((NoiseChunk)(Object)this);

        /*
            Converting lists to arrays for quick access
         */
        this.bts$interpolatorsArray = this.interpolators.toArray(new NoiseChunk.NoiseInterpolator[0]);
        this.bts$cellCachesArray = this.cellCaches.toArray(new NoiseChunk.CacheAllInCell[0]);

        /*
            Caching 1/size
         */
        this.bts$inverseCellWidth = 1.0D / (double) this.cellWidth;
        this.bts$inverseCellHeight = 1.0D / (double) this.cellHeight;

        this.cellWidthMask = this.cellWidth - 1;
        this.cellWidthShift = Integer.numberOfTrailingZeros(this.cellWidth);

        int size = this.noiseSizeXZ + 1;
        this.surfaceCache = new int[size * size];
        Arrays.fill(this.surfaceCache, Integer.MIN_VALUE);
        this.reusableContext = new CachedPointContext();
    }

    /**
     * @author Sixik
     * @reason Optimize List iteration -> Array iteration
     */
    @Overwrite
    public void selectCellYZ(int yIndex, int zIndex) {
        final NoiseChunk.NoiseInterpolator[] simpleArray = bts$interpolatorsArray;

        for (int i = 0; i < simpleArray.length; i++) {
            simpleArray[i].selectCellYZ(yIndex, zIndex);
        }

        this.fillingCell = true;
        this.cellStartBlockY = (yIndex + this.cellNoiseMinY) * this.cellHeight;
        this.cellStartBlockZ = (this.firstCellZ + zIndex) * this.cellWidth;
        ++this.arrayInterpolationCounter;

        final var array = bts$cellCachesArray;
        for (int i = 0; i < array.length; i++) {
            final NoiseChunk.CacheAllInCell cache = array[i];
            cache.noiseFiller.fillArray(cache.values, (NoiseChunk) (Object) this);
        }

        ++this.arrayInterpolationCounter;
        this.fillingCell = false;
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void updateForY(int blockY, double delta) {
        this.inCellY = blockY - this.cellStartBlockY;

        final NoiseChunk.NoiseInterpolator[] array = bts$interpolatorsArray;
        for (int i = 0; i < array.length; i++) {
            array[i].updateForY(delta);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void updateForX(int i, double d) {
        this.inCellX = i - this.cellStartBlockX;
        final NoiseChunk.NoiseInterpolator[] array = bts$interpolatorsArray;
        for (int j = 0; j < array.length; j++) {
            array[j].updateForX(d);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration. This is the HOTTEST method (called per block).
     */
    @Overwrite
    public void updateForZ(int i, double d) {
        this.inCellZ = i - this.cellStartBlockZ;
        ++this.interpolationCounter;
        final NoiseChunk.NoiseInterpolator[] array = bts$interpolatorsArray;
        for (int j = 0; j < array.length; j++) {
            array[j].updateForZ(d);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void swapSlices() {
        final NoiseChunk.NoiseInterpolator[] array = bts$interpolatorsArray;
        for (int i = 0; i < array.length; i++) {
            array[i].swapSlices();
        }
    }

    /**
     * @author Sixik
     * @reason Micro optimization
     */
    @Overwrite
    private int computePreliminarySurfaceLevel(long l) {
        final int i = (int) (l & 4294967295L);
        final int j = (int) (l >>> 32 & 4294967295L);

        final int k = this.noiseSettings.minY();
        final int h = this.noiseSettings.height();
        final int cH = this.cellHeight;

        final DensityFunction el = this.initialDensityNoJaggedness;
        final CachedPointContext cachedContext = this.reusableContext;

        for(int m = k + h; m >= k; m -= cH) {
            if (el.compute(cachedContext.update(i, m, j)) > 0.390625D) {
                return m;
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * @author Sixik
     * @reason Redirect to primitive array
     */
    @Overwrite
    public int preliminarySurfaceLevel(int i, int j) {
        final int quartX = QuartPos.fromBlock(i);
        final int quartZ = QuartPos.fromBlock(j);

        final int localX = quartX - this.firstNoiseX;
        final int localZ = quartZ - this.firstNoiseZ;

        final int size = this.noiseSizeXZ + 1;
        if (localX >= 0 && localZ >= 0 && localX < size && localZ < size) {
            final int cacheIndex = localX * size + localZ;
            final int cachedValue = this.surfaceCache[cacheIndex];

            if (cachedValue != Integer.MIN_VALUE) {
                return cachedValue;
            }

            final int blockX = QuartPos.toBlock(quartX);
            final int blockZ = QuartPos.toBlock(quartZ);
            final int result = bts$computeSurface(blockX, blockZ);
            this.surfaceCache[cacheIndex] = result;
            return result;
        }

        return bts$computeSurface(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ));
    }

    @Unique
    private int bts$computeSurface(int x, int z) {
        final int minY = this.noiseSettings.minY();
        final int maxY = minY + this.noiseSettings.height();

        final var density = this.initialDensityNoJaggedness;
        final CachedPointContext ctx = this.reusableContext;

        final int cH = this.cellHeight;
        for (int currentY = maxY; currentY >= minY; currentY -= cH) {
            ctx.update(x, currentY, z);

            if (density.compute(ctx) > 0.390625) {
                return currentY;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * @author Sixik
     * @reason Optimize key generation and remove method call overhead
     */
    @Overwrite
    Blender.BlendingOutput getOrComputeBlendingOutput(int x, int z) {
        final long key = (long)x & 0xFFFFFFFFL | ((long)z << 32);

        if (this.lastBlendingDataPos == key) {
            return this.lastBlendingOutput;
        } else {
            this.lastBlendingDataPos = key;
            final Blender.BlendingOutput result = this.blender.blendOffsetAndFactor(x, z);
            this.lastBlendingOutput = result;
            return result;
        }
    }

    @Unique private int cellWidthMask;
    @Unique private int cellWidthShift;

    /**
     * @author Sixik
     * @reason Faster floor operation
     */
    @Overwrite
    public NoiseChunk forIndex(int i) {
        // floorMod (i % 4 -> i & 3)
        int j = i & this.cellWidthMask; // z

        // floorDiv (i / 4 -> i >> 2)
        int k = i >> this.cellWidthShift;

        // l = k % cellWidth
        int l = k & this.cellWidthMask; // x

        // m = (H-1) - (k / cellWidth)
        int m = (this.cellHeight - 1) - (k >> this.cellWidthShift); // y

        this.inCellZ = j;
        this.inCellX = l;
        this.inCellY = m;
        this.arrayIndex = i;

        return (NoiseChunk)(Object) this;
    }
}
