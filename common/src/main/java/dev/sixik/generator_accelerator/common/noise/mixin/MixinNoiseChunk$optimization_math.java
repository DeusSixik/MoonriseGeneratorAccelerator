package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$InterpolatorSoAPath;
import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheCompiledFillerAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.path.NoiseChunk$FlatCache$FlatArray;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkTimingStats;
import dev.sixik.generator_accelerator.common.noise.utils.CachedPointContext;
import dev.sixik.generator_accelerator.common.noise.utils.NoiseChunkSliceProvider;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunk$optimization_math implements GA$NoiseChunk$InterpolatorSoAPath {

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
    private Map<DensityFunction, DensityFunction> wrapped;
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
    @Shadow
    @Final
    public int cellCountY;
    @Shadow
    @Final
    public int cellCountXZ;
    @Shadow
    @Final
    private NoiseChunk.FlatCache blendAlpha;
    @Shadow
    @Final
    private NoiseChunk.FlatCache blendOffset;
    @Unique
    private NoiseChunk.NoiseInterpolator[] bts$interpolatorsArray;
    @Unique
    private NoiseChunk.CacheAllInCell[] bts$cellCachesArray;
    @Unique
    private DensityFunction[] bts$cellCacheFillers;
    @Unique
    private DfcCellFillAccess[] bts$cellCacheFastFillers;
    @Unique
    private boolean[] bts$cellCacheLazyFastFillers;
    @Unique
    private boolean[] bts$cellCacheRejectedFastFillers;
    @Unique
    private boolean[] bts$cellCacheFallbackClassReported;
    @Unique
    private boolean[] bts$cellCacheFastClassReported;
    @Unique
    private double[][] bts$cellCacheValues;

    @Unique
    private double[] bts$interpolatorSlice0Flat;
    @Unique
    private double[] bts$interpolatorSlice1Flat;
    @Unique
    private int bts$interpolatorSizeY;
    @Unique
    private int bts$interpolatorPlaneSize;
    @Unique
    private double[] bts$noise000;
    @Unique
    private double[] bts$noise100;
    @Unique
    private double[] bts$noise010;
    @Unique
    private double[] bts$noise110;
    @Unique
    private double[] bts$noise001;
    @Unique
    private double[] bts$noise101;
    @Unique
    private double[] bts$noise011;
    @Unique
    private double[] bts$noise111;
    @Unique
    private double[] bts$valueXZ00;
    @Unique
    private double[] bts$valueXZ10;
    @Unique
    private double[] bts$valueXZ01;
    @Unique
    private double[] bts$valueXZ11;
    @Unique
    private double[] bts$valueZ0;
    @Unique
    private double[] bts$valueZ1;
    @Unique
    private double[] bts$value;

    @Unique
    public double bts$inverseCellWidth;
    @Unique
    public double bts$inverseCellHeight;

    @Unique
    private int[] surfaceCache;

    @Unique
    private CachedPointContext reusableContext;

    @Unique
    private double[] sliceBuffer;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;wrapped:Ljava/util/Map;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            )
    )
    private void ga$preSizeWrappedMap(CallbackInfo ci) {
        this.wrapped = new Object2ObjectOpenHashMap<>(128);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;noiseSizeXZ:I",
                    ordinal = 0,
                    opcode = Opcodes.GETFIELD)
    )
    public int bts$init(NoiseChunk noiseChunk, int value) {
        bts$optimizeValues(noiseChunk);
        return -1;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bts$initOptimizationFields(CallbackInfo ci) {
        this.sliceFillingContextProvider = new NoiseChunkSliceProvider((NoiseChunk) (Object) this);

        /*
            Converting lists to arrays for quick access
        */
        this.bts$interpolatorsArray = this.interpolators.toArray(new NoiseChunk.NoiseInterpolator[0]);
        this.bts$cellCachesArray = this.cellCaches.toArray(new NoiseChunk.CacheAllInCell[0]);
        this.bts$initCellCacheArrays();
        bts$initInterpolatorSoA();

        /*
            Caching 1/size
         */
        this.bts$inverseCellWidth = 1.0D / (double) this.cellWidth;
        this.bts$inverseCellHeight = 1.0D / (double) this.cellHeight;

        this.cellWidthMask = this.cellWidth - 1;
        this.cellWidthShift = Integer.numberOfTrailingZeros(this.cellWidth);
        this.cellWidthPowerOfTwo = Integer.bitCount(this.cellWidth) == 1;

        int size = this.noiseSizeXZ + 1;
        this.surfaceCache = new int[size * size];
        Arrays.fill(this.surfaceCache, Integer.MIN_VALUE);
        this.reusableContext = new CachedPointContext();

        this.sliceBuffer = new double[this.cellCountY + 1];
    }

    @Unique
    private void bts$initCellCacheArrays() {
        final NoiseChunk.CacheAllInCell[] caches = this.bts$cellCachesArray;
        final int length = caches.length;
        this.bts$cellCacheFillers = new DensityFunction[length];
        this.bts$cellCacheFastFillers = new DfcCellFillAccess[length];
        this.bts$cellCacheLazyFastFillers = new boolean[length];
        this.bts$cellCacheRejectedFastFillers = new boolean[length];
        this.bts$cellCacheFallbackClassReported = new boolean[length];
        this.bts$cellCacheFastClassReported = new boolean[length];
        this.bts$cellCacheValues = new double[length][];

        for (int i = 0; i < length; i++) {
            final NoiseChunk.CacheAllInCell cache = caches[i];
            final DensityFunction filler = cache.noiseFiller;
            this.bts$cellCacheFillers[i] = filler;
            this.bts$cellCacheValues[i] = cache.values;
            DfcCellFillAccess access = DfcCellFillFastPath.asFastPath(filler);
            if (access != null) {
                this.bts$cellCacheFastFillers[i] = access;
            }
        }
    }

    @Unique
    private void bts$initInterpolatorSoA() {
        final NoiseChunk.NoiseInterpolator[] array = this.bts$interpolatorsArray;
        final int length = array.length;

        this.bts$interpolatorSizeY = this.cellCountY + 1;
        this.bts$interpolatorPlaneSize = (this.cellCountXZ + 1) * this.bts$interpolatorSizeY;
        this.bts$interpolatorSlice0Flat = new double[length * this.bts$interpolatorPlaneSize];
        this.bts$interpolatorSlice1Flat = new double[length * this.bts$interpolatorPlaneSize];

        this.bts$noise000 = new double[length];
        this.bts$noise100 = new double[length];
        this.bts$noise010 = new double[length];
        this.bts$noise110 = new double[length];
        this.bts$noise001 = new double[length];
        this.bts$noise101 = new double[length];
        this.bts$noise011 = new double[length];
        this.bts$noise111 = new double[length];
        this.bts$valueXZ00 = new double[length];
        this.bts$valueXZ10 = new double[length];
        this.bts$valueXZ01 = new double[length];
        this.bts$valueXZ11 = new double[length];
        this.bts$valueZ0 = new double[length];
        this.bts$valueZ1 = new double[length];
        this.bts$value = new double[length];

        for (int i = 0; i < length; i++) {
            GA$NoiseChunk$NoiseInterpolatorPatch patch = (GA$NoiseChunk$NoiseInterpolatorPatch) array[i];
            patch.bts$setSoAIndex(i);
        }
    }

    /**
     * @author Sixik
     * @reason Optimize List iteration -> Array iteration
     */
    @Overwrite
    public void selectCellYZ(int yIndex, int zIndex) {
        long timingStart = NoiseChunkTimingStats.startSelectCellYz();
        final int base0 = zIndex * this.bts$interpolatorSizeY + yIndex;
        final int base1 = base0 + this.bts$interpolatorSizeY;
        final int planeSize = this.bts$interpolatorPlaneSize;
        final double[] slice0 = this.bts$interpolatorSlice0Flat;
        final double[] slice1 = this.bts$interpolatorSlice1Flat;

        for (int i = 0, sliceBase = 0; i < this.bts$noise000.length; i++, sliceBase += planeSize) {
            final int idx0 = sliceBase + base0;
            final int idx1 = sliceBase + base1;

            this.bts$noise000[i] = slice0[idx0];
            this.bts$noise001[i] = slice0[idx1];
            this.bts$noise100[i] = slice1[idx0];
            this.bts$noise101[i] = slice1[idx1];
            this.bts$noise010[i] = slice0[idx0 + 1];
            this.bts$noise011[i] = slice0[idx1 + 1];
            this.bts$noise110[i] = slice1[idx0 + 1];
            this.bts$noise111[i] = slice1[idx1 + 1];
        }

        this.fillingCell = true;
        this.cellStartBlockY = (yIndex + this.cellNoiseMinY) * this.cellHeight;
        this.cellStartBlockZ = (this.firstCellZ + zIndex) * this.cellWidth;
        ++this.arrayInterpolationCounter;

        final NoiseChunk self = (NoiseChunk) (Object) this;
        final NoiseChunk.CacheAllInCell[] caches = this.bts$cellCachesArray;
        final DensityFunction[] fillers = this.bts$cellCacheFillers;
        final DfcCellFillAccess[] fastFillers = this.bts$cellCacheFastFillers;
        final boolean[] lazyFastFillers = this.bts$cellCacheLazyFastFillers;
        final double[][] valuesArray = this.bts$cellCacheValues;
        long cacheFillTimingStart = NoiseChunkTimingStats.startSelectCellYzCacheFill(timingStart);
        final boolean timingStages = cacheFillTimingStart != 0L;
        for (int i = 0; i < fillers.length; i++) {
            final DensityFunction filler = fillers[i];
            DfcCellFillAccess fast = fastFillers[i];

            if (fast == null
                    && !this.bts$cellCacheRejectedFastFillers[i]
                    && caches[i] instanceof DfcCellCacheCompiledFillerAccess access) {
                long lazyResolveTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                fast = access.dfc$getOrCompileCellFiller();
                NoiseChunkTimingStats.recordLazyResolve(lazyResolveTimingStart);
                if (fast != null) {
                    fastFillers[i] = fast;
                    lazyFastFillers[i] = true;
                } else {
                    this.bts$cellCacheRejectedFastFillers[i] = true;
                }
            }

            final double[] values = valuesArray[i];
            if (fast != null) {
                this.bts$recordFastFillerClass(i, filler);
                if (DfcCellFillStats.ENABLED) {
                    DfcCellFillStats.recordCellFill(fast, filler);
                }
                long fastFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                fast.dfc$fillCell(values, self);
                NoiseChunkTimingStats.recordFastFill(fastFillTimingStart);
                if (DfcCellFillParity.isActive()) {
                    DfcCellFillParity.recordCandidate(filler, true, lazyFastFillers[i]);
                    if (!DfcCellFillParity.check(filler, values, self)) {
                        fastFillers[i] = null;
                        lazyFastFillers[i] = false;
                        this.bts$cellCacheRejectedFastFillers[i] = true;
                        this.bts$recordFallbackFillerClass(i, filler);
                        long fallbackFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                        filler.fillArray(values, self);
                        NoiseChunkTimingStats.recordFallbackFill(fallbackFillTimingStart);
                    }
                }
            } else {
                if (DfcCellFillParity.isActive()) {
                    DfcCellFillParity.recordCandidate(filler, false, false);
                }
                this.bts$recordFallbackFillerClass(i, filler);
                long fallbackFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                filler.fillArray(values, self);
                NoiseChunkTimingStats.recordFallbackFill(fallbackFillTimingStart);
            }
        }

        ++this.arrayInterpolationCounter;
        this.fillingCell = false;
        NoiseChunkTimingStats.finishSelectCellYz(timingStart, cacheFillTimingStart);
    }

    @Unique
    private void bts$recordFastFillerClass(int index, DensityFunction filler) {
        if (this.bts$cellCacheFastClassReported[index]) {
            return;
        }
        this.bts$cellCacheFastClassReported[index] = true;
        NoiseChunkTimingStats.recordFastFillerClass(filler);
        if (filler instanceof DensityFunctions.TwoArgumentSimpleFunction tas) {
            NoiseChunkTimingStats.recordFastFillerDetail(
                    "TwoArg." + tas.type()
                            + "(left=" + bts$fastPathDebug(tas.argument1())
                            + ",right=" + bts$fastPathDebug(tas.argument2())
                            + ")");
        }
    }

    @Unique
    private void bts$recordFallbackFillerClass(int index, DensityFunction filler) {
        if (this.bts$cellCacheFallbackClassReported[index]) {
            return;
        }
        this.bts$cellCacheFallbackClassReported[index] = true;
        NoiseChunkTimingStats.recordFallbackFillerClass(filler);
        if (filler instanceof DensityFunctions.TwoArgumentSimpleFunction tas) {
            NoiseChunkTimingStats.recordFallbackFillerDetail(
                    "TwoArg." + tas.type()
                            + "(left=" + bts$fastPathDebug(tas.argument1())
                            + ",right=" + bts$fastPathDebug(tas.argument2())
                            + ")");
        }
    }

    @Unique
    private static String bts$fastPathDebug(DensityFunction function) {
        boolean fast = DfcCellFillFastPath.asFastPath(function) != null;
        return (fast ? "fast:" : "slow:") + function.getClass().getName();
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void updateForY(int blockY, double delta) {
        this.inCellY = blockY - this.cellStartBlockY;

        final double[] noise000 = this.bts$noise000;
        final double[] noise100 = this.bts$noise100;
        final double[] noise001 = this.bts$noise001;
        final double[] noise101 = this.bts$noise101;
        final double[] noise010 = this.bts$noise010;
        final double[] noise110 = this.bts$noise110;
        final double[] noise011 = this.bts$noise011;
        final double[] noise111 = this.bts$noise111;
        final double[] valueXZ00 = this.bts$valueXZ00;
        final double[] valueXZ10 = this.bts$valueXZ10;
        final double[] valueXZ01 = this.bts$valueXZ01;
        final double[] valueXZ11 = this.bts$valueXZ11;
        for (int i = 0; i < noise000.length; i++) {
            final double n000 = noise000[i];
            final double n100 = noise100[i];
            final double n001 = noise001[i];
            final double n101 = noise101[i];

            valueXZ00[i] = n000 + delta * (noise010[i] - n000);
            valueXZ10[i] = n100 + delta * (noise110[i] - n100);
            valueXZ01[i] = n001 + delta * (noise011[i] - n001);
            valueXZ11[i] = n101 + delta * (noise111[i] - n101);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void updateForX(int i, double d) {
        this.inCellX = i - this.cellStartBlockX;
        final double[] valueXZ00 = this.bts$valueXZ00;
        final double[] valueXZ01 = this.bts$valueXZ01;
        final double[] valueXZ10 = this.bts$valueXZ10;
        final double[] valueXZ11 = this.bts$valueXZ11;
        final double[] valueZ0 = this.bts$valueZ0;
        final double[] valueZ1 = this.bts$valueZ1;
        for (int j = 0; j < valueXZ00.length; j++) {
            final double v0 = valueXZ00[j];
            final double v1 = valueXZ01[j];
            valueZ0[j] = v0 + d * (valueXZ10[j] - v0);
            valueZ1[j] = v1 + d * (valueXZ11[j] - v1);
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
        final double[] valueZ0 = this.bts$valueZ0;
        final double[] valueZ1 = this.bts$valueZ1;
        final double[] value = this.bts$value;
        for (int j = 0; j < valueZ0.length; j++) {
            final double v = valueZ0[j];
            value[j] = v + d * (valueZ1[j] - v);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void swapSlices() {
        double[] tmp = this.bts$interpolatorSlice0Flat;
        this.bts$interpolatorSlice0Flat = this.bts$interpolatorSlice1Flat;
        this.bts$interpolatorSlice1Flat = tmp;
    }

    @Override
    public double bts$getInterpolatorValue(int index) {
        return this.bts$value[index];
    }

    @Override
    public double bts$getInterpolatorFillingValue(int index) {
        final double deltaX = this.inCellX * this.bts$inverseCellWidth;
        final double deltaY = this.inCellY * this.bts$inverseCellHeight;
        final double deltaZ = this.inCellZ * this.bts$inverseCellWidth;

        final double n000 = this.bts$noise000[index];
        final double n100 = this.bts$noise100[index];
        final double n010 = this.bts$noise010[index];
        final double n110 = this.bts$noise110[index];
        final double n001 = this.bts$noise001[index];
        final double n101 = this.bts$noise101[index];
        final double n011 = this.bts$noise011[index];
        final double n111 = this.bts$noise111[index];

        final double lerpY00 = n000 + deltaY * (n010 - n000);
        final double lerpY10 = n100 + deltaY * (n110 - n100);
        final double lerpY01 = n001 + deltaY * (n011 - n001);
        final double lerpY11 = n101 + deltaY * (n111 - n101);

        final double lerpX0 = lerpY00 + deltaX * (lerpY10 - lerpY00);
        final double lerpX1 = lerpY01 + deltaX * (lerpY11 - lerpY01);

        return lerpX0 + deltaZ * (lerpX1 - lerpX0);
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

        for (int m = k + h; m >= k; m -= cH) {
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
        final int quartX = i >> 2;
        final int quartZ = j >> 2;

        final int localX = quartX - this.firstNoiseX;
        final int localZ = quartZ - this.firstNoiseZ;

        final int size = this.noiseSizeXZ + 1;
        if (localX >= 0 && localZ >= 0 && localX < size && localZ < size) {
            final int cacheIndex = localX * size + localZ;
            final int cachedValue = this.surfaceCache[cacheIndex];

            if (cachedValue != Integer.MIN_VALUE) {
                return cachedValue;
            }

            final int blockX = quartX << 2;
            final int blockZ = quartZ << 2;
            final int result = bts$computeSurface(blockX, blockZ);
            this.surfaceCache[cacheIndex] = result;
            return result;
        }

        return bts$computeSurface(quartX << 2, quartZ << 2);
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
    public Blender.BlendingOutput getOrComputeBlendingOutput(int x, int z) {
        final long key = (long) x & 0xFFFFFFFFL | ((long) z << 32);

        if (this.lastBlendingDataPos == key) {
            return this.lastBlendingOutput;
        } else {
            this.lastBlendingDataPos = key;
            final Blender.BlendingOutput result = this.blender.blendOffsetAndFactor(x, z);
            this.lastBlendingOutput = result;
            return result;
        }
    }

    @Unique
    private int cellWidthMask;
    @Unique
    private int cellWidthShift;
    @Unique
    private boolean cellWidthPowerOfTwo;

    /**
     * @author Sixik
     * @reason Faster floor operation
     */
    @Overwrite
    public NoiseChunk forIndex(int i) {
        final int j;
        final int l;
        final int m;
        if (this.cellWidthPowerOfTwo) {
            final int k = i >> this.cellWidthShift;
            j = i & this.cellWidthMask;
            l = k & this.cellWidthMask;
            m = (this.cellHeight - 1) - (k >> this.cellWidthShift);
        } else {
            final int k = i / this.cellWidth;
            j = i - k * this.cellWidth;
            final int yPlane = k / this.cellWidth;
            l = k - yPlane * this.cellWidth;
            m = (this.cellHeight - 1) - yPlane;
        }

        this.inCellZ = j;
        this.inCellX = l;
        this.inCellY = m;
        this.arrayIndex = i;

        return (NoiseChunk) (Object) this;
    }

    /**
     * @author Sixik
     * @reason Redirect to flat iterator
     */
    @Overwrite
    private void fillSlice(boolean pIsSlice0, int pStart) {
        long timingStart = NoiseChunkTimingStats.startFillSlice();
        this.cellStartBlockX = pStart * this.cellWidth;
        this.inCellX = 0;

        int sizeY = this.cellCountY + 1;

        for (int i = 0; i < this.cellCountXZ + 1; i++) {
            int j = this.firstCellZ + i;
            this.cellStartBlockZ = j * this.cellWidth;
            this.inCellZ = 0;
            this.arrayInterpolationCounter++;

            final NoiseChunk.NoiseInterpolator[] interpolatorsArray = this.bts$interpolatorsArray;
            final double[] target = pIsSlice0 ? this.bts$interpolatorSlice0Flat : this.bts$interpolatorSlice1Flat;
            final int zOffset = i * sizeY;
            final int planeSize = this.bts$interpolatorPlaneSize;

            for (int k = 0; k < interpolatorsArray.length; k++) {
                NoiseChunk.NoiseInterpolator noisechunk$noiseinterpolator = interpolatorsArray[k];
                noisechunk$noiseinterpolator.fillArray(this.sliceBuffer, this.sliceFillingContextProvider);
                System.arraycopy(
                        this.sliceBuffer,
                        0,
                        target,
                        k * planeSize + zOffset,
                        sizeY
                );

            }
        }

        this.arrayInterpolationCounter++;
        NoiseChunkTimingStats.finishFillSlice(timingStart);
    }

    @Unique
    private void bts$optimizeValues(NoiseChunk noiseChunk) {
        final int sizeXZ = this.noiseSizeXZ;
        final Blender blender = this.blender;

        int size = sizeXZ + 1;
        double[] flatAlpha = new double[size * size];
        double[] flatOffset = new double[size * size];

        int fX = this.firstNoiseX;
        int fZ = this.firstNoiseZ;

        for (int l = 0; l <= sizeXZ; l++) {
            int m = fX + l;
            int blockX = m << 2;
            int rowOffset = l * size;

            for (int o = 0; o <= sizeXZ; o++) {
                int p = fZ + o;
                int blockZ = p << 2;

                Blender.BlendingOutput blendingOutput = blender.blendOffsetAndFactor(blockX, blockZ);
                int index = rowOffset + o;
                flatAlpha[index] = blendingOutput.alpha();
                flatOffset[index] = blendingOutput.blendingOffset();
            }
        }

        NoiseChunk$FlatCache$FlatArray alphaAccess = (NoiseChunk$FlatCache$FlatArray) blendAlpha;
        NoiseChunk$FlatCache$FlatArray offsetAccess = (NoiseChunk$FlatCache$FlatArray) blendOffset;
        alphaAccess.bts$setArray(flatAlpha);
        offsetAccess.bts$setArray(flatOffset);
        // Keep vanilla backing arrays valid for paths that bypass the flat-array overwrite.
        alphaAccess.bts$copyFlatArrayToVanillaValues();
        offsetAccess.bts$copyFlatArrayToVanillaValues();
    }
}
