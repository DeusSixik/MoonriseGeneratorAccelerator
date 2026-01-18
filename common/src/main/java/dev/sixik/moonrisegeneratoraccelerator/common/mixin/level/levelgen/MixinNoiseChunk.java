package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.CachedPointContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.NoiseChunkPatch;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    @Unique
    private NoiseChunk.NoiseInterpolator[] bts$interpolatorsArray;
    @Unique
    private NoiseChunk.CacheAllInCell[] bts$cellCachesArray;

    @Unique
    public double bts$inverseCellWidth;
    @Unique
    public double bts$inverseCellHeight;

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
        final CachedPointContext cachedContext = new CachedPointContext();

        for(int m = k + h; m >= k; m -= cH) {
            if (el.compute(cachedContext.update(i, m, j)) > 0.390625D) {
                return m;
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * @author Sixik
     * @reason Unnecessary operations have been removed
     */
    @Overwrite
    public int preliminarySurfaceLevel(int i, int j) {
        return this.preliminarySurfaceLevel.computeIfAbsent(i & 4294967295L | ((long)j & 4294967295L) << 32, this::computePreliminarySurfaceLevel);
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
}
