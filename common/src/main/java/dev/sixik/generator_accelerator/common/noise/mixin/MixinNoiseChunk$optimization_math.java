package dev.sixik.generator_accelerator.common.noise.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$InterpolatorSoAPath;
import dev.sixik.generator_accelerator.api.patches.GA$NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheOnceWrappedAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheCompiledFillerAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.Compiler;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuIrPayload;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadBatchExecutor;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu.GpuPayloadRuntimeRegistry;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import dev.sixik.generator_accelerator.common.density.path.NoiseChunk$FlatCache$FlatArray;
import dev.sixik.generator_accelerator.common.noise.FillSliceLazyCompileBudget;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkTimingStats;
import dev.sixik.generator_accelerator.common.noise.gpu.GpuFillSliceMegaBatchDispatcher;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;

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
    private static final String FILL_SLICE_LAZY_COMPILE_PROPERTY = "ga.dfc.fillSliceLazyCompile";
    @Unique
    private static final String FILL_SLICE_LAZY_COMPILE_MAX_PROPERTY = "ga.dfc.fillSliceLazyCompile.max";
    @Unique
    private static final String FILL_SLICE_GPU_PROTOTYPE_PROPERTY = "ga.dfc.gpu.fillSlicePrototype";
    @Unique
    private static final String FILL_SLICE_GPU_ADAPTIVE_DISABLE_PROPERTY = "ga.dfc.gpu.fillSliceAdaptiveDisable";
    @Unique
    private static final String FILL_SLICE_GPU_WARM_INVOKE_MAX_NANOS_PROPERTY = "ga.dfc.gpu.fillSliceWarmInvokeMaxNanos";
    @Unique
    private static final String FILL_SLICE_GPU_SLOW_WARM_STREAK_PROPERTY = "ga.dfc.gpu.fillSliceSlowWarmStreak";
    @Unique
    private static final String CELL_CACHE_FAST_FILLERS_PROPERTY = "ga.dfc.cellCacheFastFillers";
    @Unique
    private static final java.util.concurrent.atomic.AtomicInteger bts$fillSliceGpuSlowWarmStreak =
            new java.util.concurrent.atomic.AtomicInteger();
    @Unique
    private static volatile String bts$fillSliceGpuDisabledReason = "none";
    @Unique
    private NoiseChunk.NoiseInterpolator[] bts$interpolatorsArray;
    @Unique
    private CompiledDensityFunction[] bts$fillSliceCompiledRoots;
    @Unique
    private GpuIrPayload[] bts$fillSliceGpuPayloads;
    @Unique
    private GpuFillSliceMegaBatchDispatcher.Job bts$pendingFillSliceMegaBatchJob;
    @Unique
    private Map<bts$FillSliceMegaBatchPrefetchKey, bts$FillSliceMegaBatchPrefetch> bts$prefetchedFillSliceMegaBatches;
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
    private boolean bts$cellCacheFastClassReportComplete;
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
        this.bts$compileFillSliceInterpolatorRoots();
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
        final boolean cellCacheFastFillers = Boolean.getBoolean(CELL_CACHE_FAST_FILLERS_PROPERTY);
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
            if (cellCacheFastFillers) {
                DfcCellFillAccess access = DfcCellFillFastPath.asFastPath(filler);
                if (access != null) {
                    this.bts$cellCacheFastFillers[i] = access;
                }
            }
        }
    }

    @Unique
    private void bts$compileFillSliceInterpolatorRoots() {
        final boolean gpuCollectionEnabled = bts$fillSliceGpuCollectionAvailable();
        final boolean lazyCompileEnabled = Boolean.getBoolean(FILL_SLICE_LAZY_COMPILE_PROPERTY);
        if (!lazyCompileEnabled && !gpuCollectionEnabled) {
            return;
        }

        // CPU DFC must not depend on a GPU probe being enabled. RouterPipeline may
        // already have compiled these roots; retain and use them for fillSlice.
        this.bts$fillSliceCompiledRoots = new CompiledDensityFunction[this.bts$interpolatorsArray.length];
        if (gpuCollectionEnabled) {
            this.bts$fillSliceGpuPayloads = new GpuIrPayload[this.bts$interpolatorsArray.length];
        }
        for (int i = 0; i < this.bts$interpolatorsArray.length; i++) {
            NoiseChunk.NoiseInterpolator interpolator = this.bts$interpolatorsArray[i];
            if (!(interpolator instanceof GA$NoiseChunk$NoiseInterpolatorPatch access)) {
                continue;
            }
            DensityFunction root = access.bts$getNoiseFiller();
            CompiledDensityFunction compiledRoot;
            if (root instanceof CompiledDensityFunction compiled) {
                compiledRoot = compiled;
            } else {
                if (!lazyCompileEnabled) {
                    continue;
                }
                if (!bts$claimFillSliceLazyCompile()) {
                    break;
                }
                NoiseChunkTimingStats.recordFillSliceLazyCompileAttempt();
                try {
                    DensityFunction compiled = Compiler.compile(root);
                    if (compiled instanceof CompiledDensityFunction compiledDensityFunction) {
                        compiledRoot = compiledDensityFunction;
                        NoiseChunkTimingStats.recordFillSliceLazyCompileSuccess();
                    } else {
                        NoiseChunkTimingStats.recordFillSliceLazyCompileFailure();
                        continue;
                    }
                } catch (RuntimeException | LinkageError exception) {
                    NoiseChunkTimingStats.recordFillSliceLazyCompileFailure();
                    continue;
                }
            }
            this.bts$fillSliceCompiledRoots[i] = compiledRoot;
            if (gpuCollectionEnabled) {
                this.bts$fillSliceGpuPayloads[i] = GpuPayloadRuntimeRegistry.lookup(compiledRoot);
            }
        }
    }

    @Unique
    private static boolean bts$claimFillSliceLazyCompile() {
        int maxCompiles = GpuFillSliceMegaBatchDispatcher.enabled()
                ? GpuFillSliceMegaBatchDispatcher.compileMax()
                : Math.max(0, Integer.getInteger(FILL_SLICE_LAZY_COMPILE_MAX_PROPERTY, 16));
        return FillSliceLazyCompileBudget.tryClaim(maxCompiles);
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
        final boolean timingStages = NoiseChunkTimingStats.stageTimingEnabled();
        final boolean cellFillStats = DfcCellFillStats.ENABLED;
        final boolean parityEnabled = DfcCellFillParity.ENABLED;
        final boolean cellCacheFastFillers = Boolean.getBoolean(CELL_CACHE_FAST_FILLERS_PROPERTY);
        if (!timingStages && !cellFillStats && !parityEnabled) {
            final boolean reportFastClasses = NoiseChunkTimingStats.ENABLED && !this.bts$cellCacheFastClassReportComplete;
            for (int i = 0; i < fillers.length; i++) {
                final DensityFunction filler = fillers[i];
                DfcCellFillAccess fast = fastFillers[i];

                if (cellCacheFastFillers
                        && fast == null
                        && !this.bts$cellCacheRejectedFastFillers[i]
                        && caches[i] instanceof DfcCellCacheCompiledFillerAccess access) {
                    fast = access.dfc$getOrCompileCellFiller();
                    if (fast != null) {
                        fastFillers[i] = fast;
                        lazyFastFillers[i] = true;
                    } else {
                        this.bts$cellCacheRejectedFastFillers[i] = true;
                    }
                }

                final double[] values = valuesArray[i];
                if (fast != null) {
                    if (reportFastClasses && !this.bts$cellCacheFastClassReported[i]) {
                        this.bts$recordFastFillerClass(i, filler, fast);
                    }
                    fast.dfc$fillCell(values, self);
                } else {
                    if (reportFastClasses && !this.bts$cellCacheFallbackClassReported[i]) {
                        this.bts$recordFallbackFillerClass(i, filler);
                    }
                    filler.fillArray(values, self);
                }
            }
            if (reportFastClasses) {
                this.bts$cellCacheFastClassReportComplete = true;
            }

            ++this.arrayInterpolationCounter;
            this.fillingCell = false;
            NoiseChunkTimingStats.finishSelectCellYz(timingStart, cacheFillTimingStart);
            return;
        }
        final boolean timingStats = NoiseChunkTimingStats.ENABLED;
        final boolean reportFastClasses = timingStages && !this.bts$cellCacheFastClassReportComplete;
        for (int i = 0; i < fillers.length; i++) {
            final DensityFunction filler = fillers[i];
            DfcCellFillAccess fast = fastFillers[i];

            if (cellCacheFastFillers
                    && fast == null
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
                if (reportFastClasses && !this.bts$cellCacheFastClassReported[i]) {
                    this.bts$recordFastFillerClass(i, filler, fast);
                }
                if (cellFillStats) {
                    DfcCellFillStats.recordCellFill(fast, filler);
                }
                long fastFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                fast.dfc$fillCell(values, self);
                NoiseChunkTimingStats.recordFastFill(fastFillTimingStart);
                if (parityEnabled && DfcCellFillParity.isActive()) {
                    DfcCellFillParity.recordCandidate(filler, true, lazyFastFillers[i]);
                    if (!DfcCellFillParity.check(filler, values, self)) {
                        fastFillers[i] = null;
                        lazyFastFillers[i] = false;
                        this.bts$cellCacheRejectedFastFillers[i] = true;
                        if (timingStats && !this.bts$cellCacheFallbackClassReported[i]) {
                            this.bts$recordFallbackFillerClass(i, filler);
                        }
                        long fallbackFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                        filler.fillArray(values, self);
                        NoiseChunkTimingStats.recordFallbackFill(fallbackFillTimingStart);
                    }
                }
            } else {
                if (parityEnabled && DfcCellFillParity.isActive()) {
                    DfcCellFillParity.recordCandidate(filler, false, false);
                }
                if (timingStats && !this.bts$cellCacheFallbackClassReported[i]) {
                    this.bts$recordFallbackFillerClass(i, filler);
                }
                long fallbackFillTimingStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
                filler.fillArray(values, self);
                NoiseChunkTimingStats.recordFallbackFill(fallbackFillTimingStart);
            }
        }

        if (reportFastClasses) {
            this.bts$cellCacheFastClassReportComplete = true;
        }

        ++this.arrayInterpolationCounter;
        this.fillingCell = false;
        NoiseChunkTimingStats.finishSelectCellYz(timingStart, cacheFillTimingStart);
    }

    @Unique
    private void bts$recordFastFillerClass(int index, DensityFunction filler, DfcCellFillAccess fast) {
        if (this.bts$cellCacheFastClassReported[index]) {
            return;
        }
        this.bts$cellCacheFastClassReported[index] = true;
        NoiseChunkTimingStats.recordFastFillerClass(filler);
        NoiseChunkTimingStats.recordFastFillerDetail(bts$fillerDetail(filler, fast));
    }

    @Unique
    private void bts$recordFallbackFillerClass(int index, DensityFunction filler) {
        if (this.bts$cellCacheFallbackClassReported[index]) {
            return;
        }
        this.bts$cellCacheFallbackClassReported[index] = true;
        NoiseChunkTimingStats.recordFallbackFillerClass(filler);
        NoiseChunkTimingStats.recordFallbackFillerDetail(bts$fillerDetail(filler, null));
    }

    @Unique
    private static String bts$fillerDetail(DensityFunction filler, DfcCellFillAccess fast) {
        if (filler == null) {
            return "null";
        }
        StringBuilder detail = new StringBuilder(filler.getClass().getName());
        if (fast != null && fast != filler) {
            detail.append("{fast=").append(fast.getClass().getName()).append('}');
        }
        if (filler instanceof DensityFunctions.TwoArgumentSimpleFunction fn) {
            detail.append("{type=").append(fn.type())
                    .append(",left=").append(bts$simpleDensityClassName(fn.argument1()))
                    .append(",right=").append(bts$simpleDensityClassName(fn.argument2()))
                    .append('}');
        }
        return detail.toString();
    }

    @Unique
    private static String bts$simpleDensityClassName(DensityFunction function) {
        if (function == null) {
            return "null";
        }
        String name = function.getClass().getName();
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1) : name;
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
        GpuFillSliceMegaBatchDispatcher.recordLifecycleSwapSlices(this.cellStartBlockX);
        double[] tmp = this.bts$interpolatorSlice0Flat;
        this.bts$interpolatorSlice0Flat = this.bts$interpolatorSlice1Flat;
        this.bts$interpolatorSlice1Flat = tmp;
        bts$tryPrefetchNextFillSliceMegaBatch();
    }

    @Override
    public double bts$getInverseCellWidth() {
        return this.bts$inverseCellWidth;
    }

    @Override
    public double bts$getInverseCellHeight() {
        return this.bts$inverseCellHeight;
    }

    @Override
    public double bts$getInterpolatorValue(int index) {
        return this.bts$value[index];
    }

    @Override
    public double bts$getInterpolatorFillingValue(int index) {
        return bts$getInterpolatorFillingValue(index, this.inCellX, this.inCellY, this.inCellZ);
    }

    @Override
    public double bts$getInterpolatorFillingValue(int index, int inCellX, int inCellY, int inCellZ) {
        final double deltaX = inCellX * this.bts$inverseCellWidth;
        final double deltaY = inCellY * this.bts$inverseCellHeight;
        final double deltaZ = inCellZ * this.bts$inverseCellWidth;
        return this.bts$getInterpolatorFillingValue(index, deltaX, deltaY, deltaZ);
    }

    @Override
    public double bts$getInterpolatorFillingValue(int index, double deltaX, double deltaY, double deltaZ) {
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

    @Override
    public void bts$updateFillingY(double deltaY) {
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

            valueXZ00[i] = n000 + deltaY * (noise010[i] - n000);
            valueXZ10[i] = n100 + deltaY * (noise110[i] - n100);
            valueXZ01[i] = n001 + deltaY * (noise011[i] - n001);
            valueXZ11[i] = n101 + deltaY * (noise111[i] - n101);
        }
    }

    @Override
    public void bts$updateFillingX(double deltaX) {
        final double[] valueXZ00 = this.bts$valueXZ00;
        final double[] valueXZ01 = this.bts$valueXZ01;
        final double[] valueXZ10 = this.bts$valueXZ10;
        final double[] valueXZ11 = this.bts$valueXZ11;
        final double[] valueZ0 = this.bts$valueZ0;
        final double[] valueZ1 = this.bts$valueZ1;
        for (int i = 0; i < valueXZ00.length; i++) {
            final double v0 = valueXZ00[i];
            final double v1 = valueXZ01[i];
            valueZ0[i] = v0 + deltaX * (valueXZ10[i] - v0);
            valueZ1[i] = v1 + deltaX * (valueXZ11[i] - v1);
        }
    }

    @Override
    public void bts$updateFillingZ(double deltaZ) {
        final double[] valueZ0 = this.bts$valueZ0;
        final double[] valueZ1 = this.bts$valueZ1;
        final double[] value = this.bts$value;
        for (int i = 0; i < valueZ0.length; i++) {
            final double v = valueZ0[i];
            value[i] = v + deltaZ * (valueZ1[i] - v);
        }
    }

    @Override
    public double[] bts$getValueArray() {
        return this.bts$value;
    }

    @Override
    public double[] bts$getValueXZ00Array() {
        return this.bts$valueXZ00;
    }

    @Override
    public double[] bts$getValueXZ10Array() {
        return this.bts$valueXZ10;
    }

    @Override
    public double[] bts$getValueXZ01Array() {
        return this.bts$valueXZ01;
    }

    @Override
    public double[] bts$getValueXZ11Array() {
        return this.bts$valueXZ11;
    }

    @Override
    public double[] bts$getValueZ0Array() {
        return this.bts$valueZ0;
    }

    @Override
    public double[] bts$getValueZ1Array() {
        return this.bts$valueZ1;
    }

    @Override
    public double[] bts$getNoise000Array() {
        return this.bts$noise000;
    }

    @Override
    public double[] bts$getNoise100Array() {
        return this.bts$noise100;
    }

    @Override
    public double[] bts$getNoise010Array() {
        return this.bts$noise010;
    }

    @Override
    public double[] bts$getNoise110Array() {
        return this.bts$noise110;
    }

    @Override
    public double[] bts$getNoise001Array() {
        return this.bts$noise001;
    }

    @Override
    public double[] bts$getNoise101Array() {
        return this.bts$noise101;
    }

    @Override
    public double[] bts$getNoise011Array() {
        return this.bts$noise011;
    }

    @Override
    public double[] bts$getNoise111Array() {
        return this.bts$noise111;
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
        NoiseChunkTimingStats.recordFillSliceBatchSurface(
                this.cellCountXZ + 1,
                sizeY,
                this.bts$interpolatorsArray.length);
        final int columns = this.cellCountXZ + 1;
        final NoiseChunk.NoiseInterpolator[] interpolatorsArray = this.bts$interpolatorsArray;
        final double[] target = pIsSlice0 ? this.bts$interpolatorSlice0Flat : this.bts$interpolatorSlice1Flat;
        GpuFillSliceMegaBatchDispatcher.recordLifecycleFillSlice(pIsSlice0, pStart, target);
        final int planeSize = this.bts$interpolatorPlaneSize;
        final boolean[] gpuFilledRoots;
        this.bts$pendingFillSliceMegaBatchJob = null;
        if (bts$fillSliceGpuCollectionAvailable()) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionAttempt();
            bts$recordFillSlicePayloadSurface(columns, sizeY, interpolatorsArray);
            bts$FillSliceMegaBatchUse prefetchUse = GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled()
                    ? bts$tryUsePrefetchedFillSliceMegaBatch(pIsSlice0, pStart, target, interpolatorsArray.length)
                    : new bts$FillSliceMegaBatchUse(false, null);
            gpuFilledRoots = prefetchUse.used()
                    ? prefetchUse.filledRoots()
                    : bts$tryFillSliceGpuPrototype(columns, sizeY, interpolatorsArray, target, planeSize);
        } else {
            gpuFilledRoots = null;
        }

        for (int i = 0; i < columns; i++) {
            int j = this.firstCellZ + i;
            this.cellStartBlockZ = j * this.cellWidth;
            this.inCellZ = 0;
            this.arrayInterpolationCounter++;

            final int zOffset = i * sizeY;

            for (int k = 0; k < interpolatorsArray.length; k++) {
                if (gpuFilledRoots != null && gpuFilledRoots[k]) {
                    continue;
                }
                NoiseChunk.NoiseInterpolator noisechunk$noiseinterpolator = interpolatorsArray[k];
                // The CPU generated fillArray path is not beneficial for the small
                // per-interpolator slice shape. Keep the compiled roots for the GPU
                // collector, but use the vanilla/JIT-friendly marker path on CPU.
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

        GpuFillSliceMegaBatchDispatcher.Job megaBatchJob = this.bts$pendingFillSliceMegaBatchJob;
        if (megaBatchJob != null) {
            megaBatchJob.markCompleted();
            this.bts$pendingFillSliceMegaBatchJob = null;
            if (GpuFillSliceMegaBatchDispatcher.asyncProbeEnabled()) {
                bts$tryFillSliceMegaBatchDeferredJobParity(megaBatchJob);
            } else if (GpuFillSliceMegaBatchDispatcher.dispatchEnabled()
                    && !GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
                GpuFillSliceMegaBatchDispatcher.Batch batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatch();
                if (batch.ready()) {
                    bts$dispatchFillSliceMegaBatch(batch, false);
                }
            }
        }

        this.arrayInterpolationCounter++;
        NoiseChunkTimingStats.finishFillSlice(timingStart);
    }

    @Unique
    private bts$FillSliceMegaBatchUse bts$tryUsePrefetchedFillSliceMegaBatch(
            boolean isSlice0,
            int start,
            double[] target,
            int interpolatorCount) {
        GpuFillSliceMegaBatchDispatcher.recordPrefetchConsumeAttempt();
        if (isSlice0) {
            GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("slice0");
            return new bts$FillSliceMegaBatchUse(false, null);
        }
        if (this.bts$prefetchedFillSliceMegaBatches == null
                || this.bts$prefetchedFillSliceMegaBatches.isEmpty()) {
            GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("map-empty");
            return new bts$FillSliceMegaBatchUse(false, null);
        }
        bts$FillSliceMegaBatchPrefetch prefetch = this.bts$prefetchedFillSliceMegaBatches.remove(
                new bts$FillSliceMegaBatchPrefetchKey(target, start));
        if (prefetch == null) {
            boolean targetMatch = false;
            int minDelta = Integer.MAX_VALUE;
            for (bts$FillSliceMegaBatchPrefetchKey key : this.bts$prefetchedFillSliceMegaBatches.keySet()) {
                if (key.target() == target) {
                    targetMatch = true;
                    int delta = key.start() - start;
                    if (Math.abs(delta) < Math.abs(minDelta)) {
                        minDelta = delta;
                    }
                }
            }
            if (targetMatch) {
                String direction = minDelta < 0 ? "start-before" : "start-after";
                GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss(direction);
                GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss(
                        direction + "-delta-" + Math.abs(minDelta));
            } else {
                GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("target-miss");
            }
            return new bts$FillSliceMegaBatchUse(false, null);
        }
        GpuFillSliceMegaBatchDispatcher.recordPrefetchHit();
        GpuFillSliceMegaBatchDispatcher.Job job = prefetch.job();
        this.bts$pendingFillSliceMegaBatchJob = job;
        boolean writebackAllowed = bts$fillSliceMegaBatchWritebackAllowed(job);
        if (GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                && !job.gpuDispatchStarted()
                && GpuFillSliceMegaBatchDispatcher.dispatchEnabled()
                && GpuFillSliceMegaBatchDispatcher.asyncProbeEnabled()
                && !GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
            GpuFillSliceMegaBatchDispatcher.Batch batch =
                    GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(job, false);
            if (batch.ready()) {
                GpuFillSliceMegaBatchDispatcher.recordPrefetchDispatch();
                bts$dispatchFillSliceMegaBatch(batch, true);
            }
        }
        if (bts$tryFillSliceMegaBatchWriteback(job)) {
            boolean[] filled = new boolean[interpolatorCount];
            for (int groupIndex : prefetch.groupIndices()) {
                filled[groupIndex] = true;
            }
            GpuFillSliceMegaBatchDispatcher.recordWriteback(job.combinedPointCount());
            GpuFillSliceMegaBatchDispatcher.recordPrefetchWriteback();
            return new bts$FillSliceMegaBatchUse(true, filled);
        }
        GpuFillSliceMegaBatchDispatcher.recordWritebackMiss(
                !bts$fillSliceMegaBatchWritebackAllowed(job)
                        ? "prefetch-hit-" + bts$fillSliceMegaBatchWritebackBlockedReason(job)
                        : (job.gpuDispatchStarted()
                        ? "prefetch-hit-gpu-not-ready"
                        : "prefetch-hit-gpu-not-dispatched"),
                job.combinedPointCount());
        return new bts$FillSliceMegaBatchUse(true, null);
    }

    @Unique
    private void bts$tryPrefetchNextFillSliceMegaBatch() {
        if (!GpuFillSliceMegaBatchDispatcher.enabled()
                || !GpuFillSliceMegaBatchDispatcher.dispatchEnabled()
                || !GpuFillSliceMegaBatchDispatcher.asyncProbeEnabled()
                || !GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled()
                || !GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                || !bts$fillSliceGpuCollectionAvailable()
                || GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
            return;
        }
        if (this.bts$fillSliceCompiledRoots == null || this.bts$fillSliceGpuPayloads == null
                || this.bts$interpolatorsArray == null || this.bts$interpolatorsArray.length == 0) {
            return;
        }
        GpuFillSliceMegaBatchDispatcher.recordPrefetchAttempt();

        int columns = this.cellCountXZ + 1;
        int yCount = this.cellCountY + 1;
        int pointCount;
        try {
            pointCount = Math.multiplyExact(columns, yCount);
        } catch (ArithmeticException ignored) {
            GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("prefetch-point-overflow");
            return;
        }
        ArrayList<Integer> candidateIndices = new ArrayList<>();
        ArrayList<CompiledDensityFunction> candidateRoots = new ArrayList<>();
        ArrayList<GpuIrPayload> candidatePayloads = new ArrayList<>();
        for (int k = 0; k < this.bts$interpolatorsArray.length; k++) {
            CompiledDensityFunction compiled = this.bts$fillSliceCompiledRoots[k];
            GpuIrPayload payload = this.bts$fillSliceGpuPayloads[k];
            if (compiled == null || payload == null) {
                continue;
            }
            if (GpuFillSliceMegaBatchDispatcher.enabled()
                    && !GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                    && !GpuFillSliceMegaBatchDispatcher.probePurePayloads()
                    && !payload.hasExternInputs()) {
                continue;
            }
            candidateIndices.add(k);
            candidateRoots.add(compiled);
            candidatePayloads.add(payload);
        }
        if (candidatePayloads.isEmpty()) {
            GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss("prefetch-candidates-empty");
            return;
        }

        int groupCount = candidatePayloads.size();
        int[] groupIndices = new int[groupCount];
        CompiledDensityFunction[] groupRoots = new CompiledDensityFunction[groupCount];
        GpuIrPayload[] groupPayloads = new GpuIrPayload[groupCount];
        GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots =
                new GpuFillSliceMegaBatchDispatcher.CandidateRoot[groupCount];
        int maxExternInputCount = 0;
        for (int i = 0; i < groupCount; i++) {
            groupIndices[i] = candidateIndices.get(i);
            groupRoots[i] = candidateRoots.get(i);
            groupPayloads[i] = candidatePayloads.get(i);
            roots[i] = new GpuFillSliceMegaBatchDispatcher.CandidateRoot(
                    groupIndices[i], groupRoots[i], groupPayloads[i]);
            maxExternInputCount = Math.max(maxExternInputCount, groupPayloads[i].externInputCount());
        }

        int previousCellStartBlockX = this.cellStartBlockX;
        int previousCellStartBlockZ = this.cellStartBlockZ;
        int previousInCellX = this.inCellX;
        int previousInCellZ = this.inCellZ;
        int nextStart = Math.floorDiv(previousCellStartBlockX, this.cellWidth)
                + GpuFillSliceMegaBatchDispatcher.prefetchLeadCells();
        double[] target = this.bts$interpolatorSlice1Flat;
        try {
            this.cellStartBlockX = nextStart * this.cellWidth;
            this.inCellX = 0;
            double[] externValuesSnapshot = bts$snapshotFillSliceExternValues(
                    groupRoots, groupPayloads, columns, yCount, pointCount,
                    maxExternInputCount, this.arrayInterpolationCounter);
            GpuFillSliceMegaBatchDispatcher.Job job = new GpuFillSliceMegaBatchDispatcher.Job(
                    columns,
                    yCount,
                    pointCount,
                    this.bts$interpolatorPlaneSize,
                    this.arrayInterpolationCounter,
                    this.cellStartBlockX,
                    this.firstCellZ,
                    this.cellWidth,
                    this.cellNoiseMinY,
                    this.cellHeight,
                    maxExternInputCount,
                    externValuesSnapshot,
                    target,
                    roots);
            GpuFillSliceMegaBatchDispatcher.EnqueueResult enqueueResult =
                    GpuFillSliceMegaBatchDispatcher.enqueue(job);
            if (enqueueResult != GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED) {
                GpuFillSliceMegaBatchDispatcher.recordPrefetchMiss(
                        "prefetch-enqueue-" + enqueueResult.name());
                return;
            }
            GpuFillSliceMegaBatchDispatcher.recordPrefetchQueued();
            if (this.bts$prefetchedFillSliceMegaBatches == null) {
                this.bts$prefetchedFillSliceMegaBatches = new HashMap<>();
            }
            this.bts$prefetchedFillSliceMegaBatches.put(
                    new bts$FillSliceMegaBatchPrefetchKey(target, nextStart),
                    new bts$FillSliceMegaBatchPrefetch(job, nextStart, target, groupIndices));
            GpuFillSliceMegaBatchDispatcher.recordLifecyclePrefetchStored(nextStart, target);
            GpuFillSliceMegaBatchDispatcher.Batch batch =
                    GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(job, false);
            if (batch.ready()) {
                GpuFillSliceMegaBatchDispatcher.recordPrefetchDispatch();
                bts$dispatchFillSliceMegaBatch(batch, true);
            }
        } finally {
            this.cellStartBlockX = previousCellStartBlockX;
            this.cellStartBlockZ = previousCellStartBlockZ;
            this.inCellX = previousInCellX;
            this.inCellZ = previousInCellZ;
        }
    }

    @Unique
    private boolean[] bts$tryFillSliceGpuPrototype(
            int columns,
            int yCount,
            NoiseChunk.NoiseInterpolator[] interpolators,
            double[] target,
            int planeSize) {
        if (!bts$fillSliceGpuCollectionAvailable()) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("collection-unavailable");
            return null;
        }
        if (columns <= 0 || yCount <= 0 || interpolators.length == 0) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("invalid-shape");
            return null;
        }
        if (this.bts$fillSliceCompiledRoots == null) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("compiled-roots-null");
            return null;
        }
        if (this.bts$fillSliceGpuPayloads == null) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("payload-array-null");
            return null;
        }
        if (!"none".equals(bts$fillSliceGpuDisabledReason)) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_adaptive_disabled");
            return null;
        }
        final int pointCount;
        try {
            pointCount = Math.multiplyExact(columns, yCount);
        } catch (ArithmeticException ignored) {
            return null;
        }

        ArrayList<Integer> candidateIndices = new ArrayList<>();
        ArrayList<CompiledDensityFunction> candidateRoots = new ArrayList<>();
        ArrayList<GpuIrPayload> candidatePayloads = new ArrayList<>();
        for (int k = 0; k < interpolators.length; k++) {
            CompiledDensityFunction compiled = this.bts$fillSliceCompiledRoots[k];
            GpuIrPayload payload = this.bts$fillSliceGpuPayloads[k];
            if (compiled == null || payload == null) {
                continue;
            }
            if (GpuFillSliceMegaBatchDispatcher.enabled()
                    && !GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                    && !GpuFillSliceMegaBatchDispatcher.probePurePayloads()
                    && !payload.hasExternInputs()) {
                continue;
            }
            candidateIndices.add(k);
            candidateRoots.add(compiled);
            candidatePayloads.add(payload);
        }
        if (candidatePayloads.isEmpty()) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("payload-candidates-empty");
            return null;
        }

        int groupCount = candidatePayloads.size();
        int combinedPointCount;
        try {
            combinedPointCount = Math.multiplyExact(pointCount, groupCount);
        } catch (ArithmeticException ignored) {
            return null;
        }
        NoiseChunkTimingStats.recordFillSliceGpuGroupCandidate(
                candidatePayloads.size(), groupCount, combinedPointCount);

        int[] groupIndices = new int[groupCount];
        CompiledDensityFunction[] groupRoots = new CompiledDensityFunction[groupCount];
        GpuIrPayload[] groupPayloads = new GpuIrPayload[groupCount];
        for (int i = 0; i < groupCount; i++) {
            groupIndices[i] = candidateIndices.get(i);
            groupRoots[i] = candidateRoots.get(i);
            groupPayloads[i] = candidatePayloads.get(i);
        }

        if (GpuFillSliceMegaBatchDispatcher.enabled()) {
            if (!GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                    && !GpuFillSliceMegaBatchDispatcher.probeDispatchEnabled()) {
                return null;
            }
            GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots =
                    new GpuFillSliceMegaBatchDispatcher.CandidateRoot[groupCount];
            for (int i = 0; i < groupCount; i++) {
                roots[i] = new GpuFillSliceMegaBatchDispatcher.CandidateRoot(
                        groupIndices[i], groupRoots[i], groupPayloads[i]);
            }
            int maxExternInputCount = 0;
            for (GpuIrPayload payload : groupPayloads) {
                maxExternInputCount = Math.max(maxExternInputCount, payload.externInputCount());
            }
            double[] externValuesSnapshot = bts$snapshotFillSliceExternValues(
                    groupRoots, groupPayloads, columns, yCount, pointCount,
                    maxExternInputCount, this.arrayInterpolationCounter);
            GpuFillSliceMegaBatchDispatcher.Job job = new GpuFillSliceMegaBatchDispatcher.Job(
                    columns,
                    yCount,
                    pointCount,
                    planeSize,
                    this.arrayInterpolationCounter,
                    this.cellStartBlockX,
                    this.firstCellZ,
                    this.cellWidth,
                    this.cellNoiseMinY,
                    this.cellHeight,
                    maxExternInputCount,
                    externValuesSnapshot,
                    target,
                    roots);
            GpuFillSliceMegaBatchDispatcher.EnqueueResult enqueueResult =
                    GpuFillSliceMegaBatchDispatcher.enqueue(job);
            boolean queued = enqueueResult == GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED;
            if (enqueueResult != GpuFillSliceMegaBatchDispatcher.EnqueueResult.QUEUED) {
                NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip(
                        "mega-batch-enqueue-" + enqueueResult.name());
            } else {
                this.bts$pendingFillSliceMegaBatchJob = job;
            }
            boolean directWriteback = queued
                    && GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                    && GpuFillSliceMegaBatchDispatcher.writebackWaitNanos() > 0L
                    && !GpuFillSliceMegaBatchDispatcher.prefetchNextEnabled();
            if (GpuFillSliceMegaBatchDispatcher.dispatchEnabled()
                    && GpuFillSliceMegaBatchDispatcher.asyncProbeEnabled()
                    && !GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
                GpuFillSliceMegaBatchDispatcher.Batch batch = null;
                if (directWriteback) {
                    batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatchIncluding(job, false);
                }
                if (batch == null || !batch.ready()) {
                    batch = GpuFillSliceMegaBatchDispatcher.drainReadyBatch(false);
                }
                if (batch.ready()) {
                    bts$dispatchFillSliceMegaBatch(batch, true);
                }
            }
            if (directWriteback) {
                if (bts$tryFillSliceMegaBatchWriteback(job)) {
                    boolean[] filled = new boolean[interpolators.length];
                    for (int groupIndex : groupIndices) {
                        filled[groupIndex] = true;
                    }
                    GpuFillSliceMegaBatchDispatcher.recordWriteback(job.combinedPointCount());
                    return filled;
                } else {
                    GpuFillSliceMegaBatchDispatcher.recordWritebackMiss(
                            bts$fillSliceMegaBatchWritebackMissReason(job),
                            job.combinedPointCount());
                }
            }
            return null;
        }

        if (combinedPointCount < GpuPayloadBatchExecutor.runtimeMinPoints()) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_group_below_min");
            return null;
        }
        if (GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("runtime_lock_busy_precheck");
            return null;
        }
        if (!GpuPayloadBatchExecutor.shouldAttemptRuntimeBatchAllowingSmallPrototype(combinedPointCount)) {
            return null;
        }

        long arrayCounterBase = this.arrayInterpolationCounter;
        if (bts$tryFillSliceMultiPayloadGpu(
                groupRoots,
                groupPayloads,
                columns,
                yCount,
                pointCount,
                combinedPointCount,
                target,
                groupIndices,
                planeSize,
                arrayCounterBase)) {
            boolean[] filled = new boolean[interpolators.length];
            for (int groupIndex : groupIndices) {
                filled[groupIndex] = true;
            }
            return filled;
        }
        return null;
    }

    @Unique
    private static boolean bts$fillSliceMegaBatchWritebackAllowed(
            GpuFillSliceMegaBatchDispatcher.Job job) {
        return GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                && (GpuPayloadBatchExecutor.runtimeParityRemaining() <= 0
                || job.runtimeParityPassed());
    }

    @Unique
    private static String bts$fillSliceMegaBatchWritebackBlockedReason(
            GpuFillSliceMegaBatchDispatcher.Job job) {
        if (job.runtimeParityChecked() && !job.runtimeParityPassed()) {
            return "job-parity-failed";
        }
        return "job-parity-pending";
    }

    @Unique
    private boolean bts$tryFillSliceMegaBatchWriteback(GpuFillSliceMegaBatchDispatcher.Job job) {
        if (bts$fillSliceMegaBatchWritebackAllowed(job) && job.writeGpuValuesToTarget()) {
            return true;
        }
        long waitNanos = GpuFillSliceMegaBatchDispatcher.writebackWaitNanos();
        if (waitNanos <= 0L || !bts$shouldWaitForFillSliceMegaBatchWriteback(job)) {
            return false;
        }

        long start = System.nanoTime();
        boolean success = false;
        do {
            if (bts$fillSliceMegaBatchWritebackAllowed(job) && job.writeGpuValuesToTarget()) {
                success = true;
                break;
            }
            if (!bts$shouldWaitForFillSliceMegaBatchWriteback(job)) {
                break;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() - start < waitNanos);

        if (!success) {
            success = bts$fillSliceMegaBatchWritebackAllowed(job) && job.writeGpuValuesToTarget();
        }
        GpuFillSliceMegaBatchDispatcher.recordWritebackWait(System.nanoTime() - start, success);
        return success;
    }

    @Unique
    private static boolean bts$shouldWaitForFillSliceMegaBatchWriteback(
            GpuFillSliceMegaBatchDispatcher.Job job) {
        return job.gpuDispatchInFlight()
                || (job.backgroundDispatchSubmitted() && !job.gpuDispatchStarted());
    }

    @Unique
    private static String bts$fillSliceMegaBatchWritebackMissReason(
            GpuFillSliceMegaBatchDispatcher.Job job) {
        if (job.gpuValuesSnapshot() != null && !bts$fillSliceMegaBatchWritebackAllowed(job)) {
            return bts$fillSliceMegaBatchWritebackBlockedReason(job);
        }
        if (!job.gpuDispatchStarted()) {
            return job.backgroundDispatchSubmitted() ? "background-not-started" : "gpu-not-dispatched";
        }
        if (job.gpuDispatchInFlight()) {
            return job.backgroundDispatchSubmitted() ? "background-in-flight" : "gpu-in-flight";
        }
        if (job.gpuValuesSnapshot() == null) {
            return job.backgroundDispatchSubmitted() ? "background-completed-not-ready" : "gpu-not-ready";
        }
        return "gpu-writeback-failed";
    }

    @Unique
    private double[] bts$snapshotFillSliceExternValues(
            CompiledDensityFunction[] roots,
            GpuIrPayload[] payloads,
            int columns,
            int yCount,
            int pointCount,
            int maxExternInputCount,
            long arrayCounterBase) {
        if (maxExternInputCount <= 0) {
            return null;
        }
        final int combinedPointCount;
        try {
            combinedPointCount = Math.multiplyExact(pointCount, roots.length);
        } catch (ArithmeticException ignored) {
            return null;
        }
        final double[] externValues;
        try {
            externValues = new double[Math.multiplyExact(combinedPointCount, maxExternInputCount)];
        } catch (ArithmeticException | OutOfMemoryError ignored) {
            return null;
        }
        for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
            if (!bts$fillSliceExternInputValues(
                    payloads[rootIndex], roots[rootIndex], columns, yCount, externValues,
                    rootIndex * pointCount, maxExternInputCount, arrayCounterBase)) {
                return null;
            }
        }
        return externValues;
    }

    @Unique
    private void bts$dispatchFillSliceMegaBatch(
            GpuFillSliceMegaBatchDispatcher.Batch batch,
            boolean deferParity) {
        if (!GpuFillSliceMegaBatchDispatcher.submitBackgroundDispatch(
                batch,
                scheduledBatch -> bts$tryFillSliceMegaBatchDispatchProbe(scheduledBatch, deferParity))) {
            bts$tryFillSliceMegaBatchDispatchProbe(batch, deferParity);
        }
    }

    @Unique
    private void bts$tryFillSliceMegaBatchDispatchProbe(
            GpuFillSliceMegaBatchDispatcher.Batch batch,
            boolean deferParity) {
        long totalStart = System.nanoTime();
        long invokeNanos = 0L;
        long parityNanos = 0L;
        long points = batch.combinedPointCount();
        List<GpuFillSliceMegaBatchDispatcher.Job> dispatchJobs = List.of();
        boolean dispatchInFlightMarked = false;
        try {
            int pointCount = batch.shapeKey().pointCount();
            int combinedPointCount = Math.toIntExact(points);
            int payloadCount = Math.toIntExact(points / pointCount);
            int maxExternInputCount = batch.shapeKey().maxExternInputs();
            if (GpuPayloadBatchExecutor.runtimeLaunchWouldSkipForBusyLock()) {
                RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_mega_batch_runtime_lock_busy_precheck");
                GpuFillSliceMegaBatchDispatcher.recordDispatchSkip(points);
                return;
            }
            if (maxExternInputCount > 0) {
                for (GpuFillSliceMegaBatchDispatcher.Job job : batch.jobs()) {
                    if (!job.hasExternValuesSnapshot()) {
                        RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_mega_batch_missing_extern_snapshot");
                        GpuFillSliceMegaBatchDispatcher.recordDispatchSkip(points);
                        return;
                    }
                }
            }
            GpuFillSliceMegaBatchDispatcher.recordDispatchAttempt(points);

            GpuIrPayload[] payloads = new GpuIrPayload[payloadCount];
            int payloadIndex = 0;
            for (GpuFillSliceMegaBatchDispatcher.Job job : batch.jobs()) {
                for (GpuFillSliceMegaBatchDispatcher.CandidateRoot root : job.roots()) {
                    payloads[payloadIndex++] = root.payload();
                }
            }
            bts$FillSliceMultiPayload packed = bts$packFillSlicePayloads(payloads);
            GpuPayloadBatchExecutor.BatchBuffers buffers = GpuPayloadBatchExecutor.localBuffers(
                    combinedPointCount, packed.scratchStride(), Math.max(1, packed.maxExternInputCount()));
            int[] blockX = buffers.blockX();
            int[] blockY = buffers.blockY();
            int[] blockZ = buffers.blockZ();
            double[] externValues = buffers.externValues();
            if (packed.maxExternInputCount() > 0) {
                Arrays.fill(externValues, 0, combinedPointCount * packed.maxExternInputCount(), 0.0D);
            }

            payloadIndex = 0;
            for (GpuFillSliceMegaBatchDispatcher.Job job : batch.jobs()) {
                GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots = job.roots();
                for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                    int pointOffset = payloadIndex * pointCount;
                    job.fillCoordinates(blockX, blockY, blockZ, pointOffset);
                    if (packed.maxExternInputCount() > 0) {
                        System.arraycopy(
                                job.externValuesSnapshot(),
                                (rootIndex * pointCount) * packed.maxExternInputCount(),
                                externValues,
                                pointOffset * packed.maxExternInputCount(),
                                pointCount * packed.maxExternInputCount());
                    }
                    payloadIndex++;
                }
            }

            double[] gpuOutput = buffers.output();
            RouterPipeline.recordGpuPayloadBatchAttempt(combinedPointCount);
            dispatchJobs = batch.jobs();
            for (GpuFillSliceMegaBatchDispatcher.Job job : dispatchJobs) {
                job.markGpuDispatchStarted();
            }
            dispatchInFlightMarked = true;
            long invokeStart = System.nanoTime();
            GpuPayloadBatchExecutor.GpuAttempt attempt = GpuPayloadBatchExecutor.tryComputeGpuRuntimeMultiPayloadBatch(
                    payloadCount,
                    pointCount,
                    packed.maxExternInputCount(),
                    packed.scratchStride(),
                    packed.payloadNodeOffsets(),
                    packed.payloadNodeCounts(),
                    packed.payloadRootIndices(),
                    blockX,
                    blockY,
                    blockZ,
                    packed.opcodes(),
                    packed.arg0(),
                    packed.arg1(),
                    packed.arg2(),
                    packed.int0(),
                    packed.int1(),
                    packed.value0(),
                    packed.value1(),
                    packed.noisePermutations(),
                    packed.noiseOctaveData(),
                    externValues,
                    gpuOutput,
                    buffers.scratch());
            invokeNanos = System.nanoTime() - invokeStart;
            RouterPipeline.recordGpuPayloadBatchArgumentLayout(
                    GpuPayloadBatchExecutor.preparedLauncherStaticArguments(),
                    GpuPayloadBatchExecutor.preparedLauncherDynamicArguments());
            if (!attempt.success()) {
                RouterPipeline.recordGpuPayloadBatchTimings(
                        0L, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                        attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                if (!attempt.preparedLauncherInvoked() && !attempt.disablesGpu()) {
                    GpuFillSliceMegaBatchDispatcher.recordDispatchSkip(points);
                    return;
                }
                if (attempt.disablesGpu()) {
                    GpuPayloadBatchExecutor.disableGpuForLifecycle(attempt.failureReason());
                }
                RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, attempt.failureReason());
                GpuFillSliceMegaBatchDispatcher.recordDispatchFailure(points);
                return;
            }

            if (deferParity) {
                payloadIndex = 0;
                for (GpuFillSliceMegaBatchDispatcher.Job job : batch.jobs()) {
                    job.markGpuCompleted(gpuOutput, payloadIndex);
                    if (job.completed()) {
                        bts$tryFillSliceMegaBatchDeferredJobParity(job);
                    }
                    payloadIndex += job.rootCount();
                }
                RouterPipeline.recordGpuPayloadBatchGpuSuccess(combinedPointCount);
                GpuFillSliceMegaBatchDispatcher.recordDispatchGpuSuccess(points);
            } else {
                long parityStart = System.nanoTime();
                GpuPayloadBatchExecutor.RuntimeParityReport parity = bts$fillSliceMegaBatchTargetParity(
                        batch, pointCount, gpuOutput, buffers.parityExpected(), combinedPointCount);
                parityNanos = System.nanoTime() - parityStart;
                RouterPipeline.recordGpuPayloadBatchRuntimeParity(
                        parity.checked(), parity.passed(), parity.pointsChecked(),
                        parity.maxAbsError(), parity.failureReason());
                if (parity.checked() && !parity.passed()) {
                    RouterPipeline.recordGpuPayloadBatchTimings(
                            0L, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                            attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                    RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                    GpuPayloadBatchExecutor.disableGpuForLifecycle(parity.failureReason());
                    RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, parity.failureReason());
                    GpuFillSliceMegaBatchDispatcher.recordDispatchFailure(points);
                    return;
                }
                RouterPipeline.recordGpuPayloadBatchGpuSuccess(combinedPointCount);
                GpuFillSliceMegaBatchDispatcher.recordDispatchGpuSuccess(points);
            }
            RouterPipeline.recordGpuPayloadBatchTimings(
                    0L, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                    attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
            RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
        } catch (RuntimeException | LinkageError exception) {
            RouterPipeline.recordGpuPayloadBatchTimings(
                    0L, invokeNanos, parityNanos, System.nanoTime() - totalStart);
            GpuPayloadBatchExecutor.disableGpuForLifecycle(exception.toString());
            RouterPipeline.recordGpuPayloadBatchCpuFallback(
                    points > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) points),
                    exception.toString());
            GpuFillSliceMegaBatchDispatcher.recordDispatchFailure(points);
        } finally {
            if (dispatchInFlightMarked) {
                for (GpuFillSliceMegaBatchDispatcher.Job job : dispatchJobs) {
                    job.markGpuDispatchFinished();
                }
            }
        }
    }

    @Unique
    private void bts$tryFillSliceMegaBatchDeferredJobParity(GpuFillSliceMegaBatchDispatcher.Job job) {
        double[] gpuSnapshot = job.gpuValuesSnapshot();
        double[] targetSnapshot = job.targetValuesSnapshot();
        if (gpuSnapshot == null || targetSnapshot == null) {
            return;
        }
        int combinedPointCount = Math.multiplyExact(job.pointCount(), job.rootCount());
        GpuPayloadBatchExecutor.RuntimeParityReport parity =
                GpuPayloadBatchExecutor.checkRuntimeParityAgainstExpected(
                        gpuSnapshot, targetSnapshot, combinedPointCount);
        RouterPipeline.recordGpuPayloadBatchRuntimeParity(
                parity.checked(), parity.passed(), parity.pointsChecked(),
                parity.maxAbsError(), parity.failureReason());
        if (parity.checked()) {
            job.markRuntimeParity(parity.passed());
        }
        if (parity.checked() && !parity.passed()) {
            GpuPayloadBatchExecutor.disableGpuForLifecycle(parity.failureReason());
            RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, parity.failureReason());
            GpuFillSliceMegaBatchDispatcher.recordDispatchFailure(combinedPointCount);
        }
    }

    @Unique
    private static GpuPayloadBatchExecutor.RuntimeParityReport bts$fillSliceMegaBatchTargetParity(
            GpuFillSliceMegaBatchDispatcher.Batch batch,
            int pointCount,
            double[] gpuOutput,
            double[] expected,
            int combinedPointCount) {
        if (GpuPayloadBatchExecutor.runtimeParityRemaining() <= 0) {
            return GpuPayloadBatchExecutor.RuntimeParityReport.skipped();
        }
        int payloadIndex = 0;
        for (GpuFillSliceMegaBatchDispatcher.Job job : batch.jobs()) {
            double[] targetSnapshot = job.targetValuesSnapshot();
            if (targetSnapshot == null || targetSnapshot.length < job.pointCount() * job.rootCount()) {
                return GpuPayloadBatchExecutor.RuntimeParityReport.failed(
                        combinedPointCount, 0.0D, "fill-slice mega-batch target snapshot missing");
            }
            GpuFillSliceMegaBatchDispatcher.CandidateRoot[] roots = job.roots();
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                System.arraycopy(
                        targetSnapshot,
                        rootIndex * pointCount,
                        expected,
                        payloadIndex * pointCount,
                        pointCount);
                payloadIndex++;
            }
        }
        return GpuPayloadBatchExecutor.checkRuntimeParityAgainstExpected(gpuOutput, expected, combinedPointCount);
    }

    @Unique
    private static GpuIrPayload bts$bestFillSlicePayloadGroup(List<GpuIrPayload> payloads) {
        IdentityHashMap<GpuIrPayload, Integer> counts = new IdentityHashMap<>();
        GpuIrPayload best = null;
        int bestCount = 0;
        for (GpuIrPayload payload : payloads) {
            int count = counts.merge(payload, 1, Integer::sum);
            if (best == null
                    || count > bestCount
                    || (count == bestCount && best.hasExternInputs() && !payload.hasExternInputs())) {
                best = payload;
                bestCount = count;
            }
        }
        return best;
    }

    @Unique
    private static int bts$countFillSlicePayloadGroup(List<GpuIrPayload> payloads, GpuIrPayload target) {
        int count = 0;
        for (GpuIrPayload payload : payloads) {
            if (payload == target) {
                count++;
            }
        }
        return count;
    }

    @Unique
    private boolean bts$tryFillSliceMultiPayloadGpu(
            CompiledDensityFunction[] roots,
            GpuIrPayload[] payloads,
            int columns,
            int yCount,
            int pointCount,
            int combinedPointCount,
            double[] target,
            int[] targetIndices,
            int planeSize,
            long arrayCounterBase) {
        long totalStart = System.nanoTime();
        long externNanos = 0L;
        long invokeNanos = 0L;
        long parityNanos = 0L;
        try {
            bts$FillSliceMultiPayload packed = bts$packFillSlicePayloads(payloads);
            GpuPayloadBatchExecutor.BatchBuffers buffers = GpuPayloadBatchExecutor.localBuffers(
                    combinedPointCount, packed.scratchStride(), packed.maxExternInputCount());
            int[] blockX = buffers.blockX();
            int[] blockY = buffers.blockY();
            int[] blockZ = buffers.blockZ();
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                bts$fillSliceCoordinates(columns, yCount, blockX, blockY, blockZ, rootIndex * pointCount);
            }

            double[] externValues = buffers.externValues();
            long externStart = System.nanoTime();
            if (packed.maxExternInputCount() > 0) {
                java.util.Arrays.fill(externValues, 0, combinedPointCount * packed.maxExternInputCount(), 0.0D);
            }
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                if (!bts$fillSliceExternInputValues(
                        payloads[rootIndex], roots[rootIndex], columns, yCount, externValues,
                        rootIndex * pointCount, packed.maxExternInputCount(), arrayCounterBase)) {
                    return false;
                }
            }
            externNanos = System.nanoTime() - externStart;

            double[] gpuOutput = buffers.output();
            RouterPipeline.recordGpuPayloadBatchAttempt(combinedPointCount);
            long invokeStart = System.nanoTime();
            GpuPayloadBatchExecutor.GpuAttempt attempt = GpuPayloadBatchExecutor.tryComputeGpuRuntimeMultiPayloadBatch(
                    payloads.length,
                    pointCount,
                    packed.maxExternInputCount(),
                    packed.scratchStride(),
                    packed.payloadNodeOffsets(),
                    packed.payloadNodeCounts(),
                    packed.payloadRootIndices(),
                    blockX,
                    blockY,
                    blockZ,
                    packed.opcodes(),
                    packed.arg0(),
                    packed.arg1(),
                    packed.arg2(),
                    packed.int0(),
                    packed.int1(),
                    packed.value0(),
                    packed.value1(),
                    packed.noisePermutations(),
                    packed.noiseOctaveData(),
                    externValues,
                    gpuOutput,
                    buffers.scratch());
            invokeNanos = System.nanoTime() - invokeStart;
            RouterPipeline.recordGpuPayloadBatchArgumentLayout(
                    GpuPayloadBatchExecutor.preparedLauncherStaticArguments(),
                    GpuPayloadBatchExecutor.preparedLauncherDynamicArguments());
            if (!attempt.success()) {
                RouterPipeline.recordGpuPayloadBatchTimings(
                        externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                        attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                if (attempt.disablesGpu()) {
                    GpuPayloadBatchExecutor.disableGpuForLifecycle(attempt.failureReason());
                }
                RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, attempt.failureReason());
                return false;
            }

            long parityStart = System.nanoTime();
            GpuPayloadBatchExecutor.RuntimeParityReport parity = bts$fillSliceMultiPayloadRuntimeParity(
                    roots, columns, yCount, pointCount, gpuOutput, buffers.parityExpected(),
                    arrayCounterBase, combinedPointCount);
            parityNanos = System.nanoTime() - parityStart;
            RouterPipeline.recordGpuPayloadBatchRuntimeParity(
                    parity.checked(), parity.passed(), parity.pointsChecked(),
                    parity.maxAbsError(), parity.failureReason());
            if (parity.checked() && !parity.passed()) {
                RouterPipeline.recordGpuPayloadBatchTimings(
                        externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                        attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                GpuPayloadBatchExecutor.disableGpuForLifecycle(parity.failureReason());
                RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, parity.failureReason());
                return false;
            }

            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                System.arraycopy(
                        gpuOutput,
                        rootIndex * pointCount,
                        target,
                        targetIndices[rootIndex] * planeSize,
                        pointCount);
                if (payloads[rootIndex].externInputCount() == 0) {
                    bts$advanceFillSliceProviderState(columns, yCount);
                }
            }
            RouterPipeline.recordGpuPayloadBatchTimings(
                    externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                    attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
            RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
            RouterPipeline.recordGpuPayloadBatchGpuSuccess(combinedPointCount);
            NoiseChunkTimingStats.recordFillSliceGpuGroupLaunch(roots.length, combinedPointCount);
            bts$recordFillSliceGpuWarmTiming(attempt, invokeNanos);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            RouterPipeline.recordGpuPayloadBatchTimings(
                    externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart);
            GpuPayloadBatchExecutor.disableGpuForLifecycle(exception.toString());
            RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, exception.toString());
            return false;
        }
    }

    @Unique
    private static void bts$recordFillSliceGpuWarmTiming(
            GpuPayloadBatchExecutor.GpuAttempt attempt,
            long invokeNanos) {
        if (!bts$fillSliceGpuAdaptiveDisableEnabled()
                || !attempt.preparedLauncherCacheHit()
                || !attempt.preparedLauncherInvoked()) {
            return;
        }
        long maxWarmInvokeNanos = bts$fillSliceGpuWarmInvokeMaxNanos();
        if (invokeNanos <= maxWarmInvokeNanos) {
            bts$fillSliceGpuSlowWarmStreak.set(0);
            return;
        }
        RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_slow_warm_invoke");
        int streak = bts$fillSliceGpuSlowWarmStreak.incrementAndGet();
        if (streak >= bts$fillSliceGpuSlowWarmStreakThreshold()) {
            bts$fillSliceGpuDisabledReason = "warm invoke " + invokeNanos
                    + "ns > " + maxWarmInvokeNanos + "ns";
            RouterPipeline.recordGpuPayloadBatchRuntimeGate("fill_slice_adaptive_disable_triggered");
        }
    }

    @Unique
    private static boolean bts$fillSliceGpuAdaptiveDisableEnabled() {
        return Boolean.parseBoolean(System.getProperty(FILL_SLICE_GPU_ADAPTIVE_DISABLE_PROPERTY, "true"));
    }

    @Unique
    private static boolean bts$fillSliceGpuPrototypeEnabled() {
        return Boolean.getBoolean(FILL_SLICE_GPU_PROTOTYPE_PROPERTY);
    }

    @Unique
    private static boolean bts$fillSliceGpuPrototypeAvailable() {
        return bts$fillSliceGpuPrototypeEnabled() && "none".equals(bts$fillSliceGpuDisabledReason);
    }

    @Unique
    private static boolean bts$fillSliceGpuCollectionAvailable() {
        return (bts$fillSliceLegacyGpuPrototypeAvailable()
                || bts$fillSliceMegaBatchCollectionAvailable())
                && "none".equals(bts$fillSliceGpuDisabledReason);
    }

    @Unique
    private static boolean bts$fillSliceLegacyGpuPrototypeAvailable() {
        return bts$fillSliceGpuPrototypeEnabled()
                && !GpuFillSliceMegaBatchDispatcher.enabled();
    }

    @Unique
    private static boolean bts$fillSliceMegaBatchCollectionAvailable() {
        return GpuFillSliceMegaBatchDispatcher.enabled()
                && (GpuFillSliceMegaBatchDispatcher.writebackEnabled()
                || GpuFillSliceMegaBatchDispatcher.probeDispatchEnabled());
    }

    @Unique
    private static long bts$fillSliceGpuWarmInvokeMaxNanos() {
        return Math.max(1L, Long.getLong(FILL_SLICE_GPU_WARM_INVOKE_MAX_NANOS_PROPERTY, 1_000_000L));
    }

    @Unique
    private static int bts$fillSliceGpuSlowWarmStreakThreshold() {
        return Math.max(1, Integer.getInteger(FILL_SLICE_GPU_SLOW_WARM_STREAK_PROPERTY, 2));
    }

    @Unique
    private GpuPayloadBatchExecutor.RuntimeParityReport bts$fillSliceMultiPayloadRuntimeParity(
            CompiledDensityFunction[] roots,
            int columns,
            int yCount,
            int pointCount,
            double[] gpuOutput,
            double[] expected,
            long arrayCounterBase,
            int combinedPointCount) {
        if (GpuPayloadBatchExecutor.runtimeParityRemaining() <= 0) {
            return GpuPayloadBatchExecutor.RuntimeParityReport.skipped();
        }
        for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
            bts$fillSliceCpuExpected(
                    roots[rootIndex], columns, yCount, expected,
                    rootIndex * pointCount, arrayCounterBase);
        }
        return GpuPayloadBatchExecutor.checkRuntimeParityAgainstExpected(gpuOutput, expected, combinedPointCount);
    }

    @Unique
    private static bts$FillSliceMultiPayload bts$packFillSlicePayloads(GpuIrPayload[] payloads) {
        int payloadCount = payloads.length;
        int totalNodes = 0;
        int maxNodeCount = 0;
        int maxExternInputCount = 0;
        int totalNoisePermutations = 0;
        int totalNoiseOctaveData = 0;
        for (GpuIrPayload payload : payloads) {
            totalNodes = Math.addExact(totalNodes, payload.nodeCount());
            maxNodeCount = Math.max(maxNodeCount, payload.nodeCount());
            maxExternInputCount = Math.max(maxExternInputCount, payload.externInputCount());
            totalNoisePermutations = Math.addExact(totalNoisePermutations, payload.noisePermutations().length);
            totalNoiseOctaveData = Math.addExact(totalNoiseOctaveData, payload.noiseOctaveData().length);
        }

        int[] payloadNodeOffsets = new int[payloadCount];
        int[] payloadNodeCounts = new int[payloadCount];
        int[] payloadRootIndices = new int[payloadCount];
        int[] opcodes = new int[totalNodes];
        int[] arg0 = new int[totalNodes];
        int[] arg1 = new int[totalNodes];
        int[] arg2 = new int[totalNodes];
        int[] int0 = new int[totalNodes];
        int[] int1 = new int[totalNodes];
        double[] value0 = new double[totalNodes];
        double[] value1 = new double[totalNodes];
        int[] noisePermutations = new int[totalNoisePermutations];
        double[] noiseOctaveData = new double[totalNoiseOctaveData];

        int nodeOffset = 0;
        int noisePermutationOffset = 0;
        int noiseOctaveDataOffset = 0;
        int noiseOctaveOffset = 0;
        for (int payloadIndex = 0; payloadIndex < payloadCount; payloadIndex++) {
            GpuIrPayload payload = payloads[payloadIndex];
            int nodeCount = payload.nodeCount();
            payloadNodeOffsets[payloadIndex] = nodeOffset;
            payloadNodeCounts[payloadIndex] = nodeCount;
            payloadRootIndices[payloadIndex] = payload.rootIndex();

            System.arraycopy(payload.opcodes(), 0, opcodes, nodeOffset, nodeCount);
            System.arraycopy(payload.arg0(), 0, arg0, nodeOffset, nodeCount);
            System.arraycopy(payload.arg1(), 0, arg1, nodeOffset, nodeCount);
            System.arraycopy(payload.arg2(), 0, arg2, nodeOffset, nodeCount);
            System.arraycopy(payload.int1(), 0, int1, nodeOffset, nodeCount);
            System.arraycopy(payload.value0(), 0, value0, nodeOffset, nodeCount);
            System.arraycopy(payload.value1(), 0, value1, nodeOffset, nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                int opcode = payload.opcodes()[i];
                int value = payload.int0()[i];
                int0[nodeOffset + i] = opcode == GpuIrPayload.INLINED_NOISE
                        || opcode == GpuIrPayload.INLINED_BLENDED_NOISE
                        ? value + noiseOctaveOffset
                        : value;
            }
            System.arraycopy(payload.noisePermutations(), 0,
                    noisePermutations, noisePermutationOffset, payload.noisePermutations().length);
            System.arraycopy(payload.noiseOctaveData(), 0,
                    noiseOctaveData, noiseOctaveDataOffset, payload.noiseOctaveData().length);

            nodeOffset += nodeCount;
            noisePermutationOffset += payload.noisePermutations().length;
            noiseOctaveDataOffset += payload.noiseOctaveData().length;
            noiseOctaveOffset += payload.noiseOctaveCount();
        }

        return new bts$FillSliceMultiPayload(
                maxNodeCount,
                maxExternInputCount,
                payloadNodeOffsets,
                payloadNodeCounts,
                payloadRootIndices,
                opcodes,
                arg0,
                arg1,
                arg2,
                int0,
                int1,
                value0,
                value1,
                noisePermutations,
                noiseOctaveData);
    }

    @Unique
    private record bts$FillSliceMegaBatchPrefetchKey(double[] target, int start) {
    }

    @Unique
    private record bts$FillSliceMegaBatchPrefetch(
            GpuFillSliceMegaBatchDispatcher.Job job,
            int start,
            double[] target,
            int[] groupIndices) {
    }

    @Unique
    private record bts$FillSliceMegaBatchUse(boolean used, boolean[] filledRoots) {
    }

    private record bts$FillSliceMultiPayload(
            int scratchStride,
            int maxExternInputCount,
            int[] payloadNodeOffsets,
            int[] payloadNodeCounts,
            int[] payloadRootIndices,
            int[] opcodes,
            int[] arg0,
            int[] arg1,
            int[] arg2,
            int[] int0,
            int[] int1,
            double[] value0,
            double[] value1,
            int[] noisePermutations,
            double[] noiseOctaveData) {
    }

    @Unique
    private boolean bts$tryFillSliceRootGroupGpu(
            CompiledDensityFunction[] roots,
            GpuIrPayload payload,
            int columns,
            int yCount,
            int pointCount,
            int combinedPointCount,
            double[] target,
            int[] targetIndices,
            int planeSize,
            long arrayCounterBase) {
        long totalStart = System.nanoTime();
        long externNanos = 0L;
        long invokeNanos = 0L;
        long parityNanos = 0L;
        try {
            int launchPointCount = bts$fillSliceLaunchPointCount(combinedPointCount);
            GpuPayloadBatchExecutor.BatchBuffers buffers = GpuPayloadBatchExecutor.localBuffers(
                    launchPointCount, payload.nodeCount(), payload.externInputCount());
            int[] blockX = buffers.blockX();
            int[] blockY = buffers.blockY();
            int[] blockZ = buffers.blockZ();
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                bts$fillSliceCoordinates(columns, yCount, blockX, blockY, blockZ, rootIndex * pointCount);
            }

            double[] externValues = buffers.externValues();
            long externStart = System.nanoTime();
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                if (!bts$fillSliceExternInputValues(
                        payload, roots[rootIndex], columns, yCount, externValues,
                        rootIndex * pointCount, payload.externInputCount(), arrayCounterBase)) {
                    return false;
                }
            }
            bts$clearFillSlicePadding(combinedPointCount, launchPointCount, payload.externInputCount(),
                    blockX, blockY, blockZ, externValues);
            externNanos = System.nanoTime() - externStart;

            double[] gpuOutput = buffers.output();
            RouterPipeline.recordGpuPayloadBatchAttempt(combinedPointCount);
            long invokeStart = System.nanoTime();
            GpuPayloadBatchExecutor.GpuAttempt attempt = GpuPayloadBatchExecutor.tryComputeGpu(
                    payload, blockX, blockY, blockZ, externValues, gpuOutput, buffers.scratch());
            invokeNanos = System.nanoTime() - invokeStart;
            RouterPipeline.recordGpuPayloadBatchArgumentLayout(
                    GpuPayloadBatchExecutor.preparedLauncherStaticArguments(),
                    GpuPayloadBatchExecutor.preparedLauncherDynamicArguments());
            if (!attempt.success()) {
                RouterPipeline.recordGpuPayloadBatchTimings(
                        externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                        attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                if (attempt.disablesGpu()) {
                    GpuPayloadBatchExecutor.disableGpuForLifecycle(attempt.failureReason());
                }
                RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, attempt.failureReason());
                return false;
            }

            long parityStart = System.nanoTime();
            GpuPayloadBatchExecutor.RuntimeParityReport parity = bts$fillSliceRuntimeParity(
                    roots, payload, columns, yCount, pointCount, blockX, blockY, blockZ,
                    externValues, gpuOutput, buffers.parityExpected(), buffers.parityPayloadExpected(),
                    arrayCounterBase, combinedPointCount);
            parityNanos = System.nanoTime() - parityStart;
            RouterPipeline.recordGpuPayloadBatchRuntimeParity(
                    parity.checked(), parity.passed(), parity.pointsChecked(),
                    parity.maxAbsError(), parity.failureReason());
            if (parity.checked() && !parity.passed()) {
                RouterPipeline.recordGpuPayloadBatchTimings(
                        externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                        attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
                RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
                GpuPayloadBatchExecutor.disableGpuForLifecycle(parity.failureReason());
                RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, parity.failureReason());
                return false;
            }

            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                System.arraycopy(
                        gpuOutput,
                        rootIndex * pointCount,
                        target,
                        targetIndices[rootIndex] * planeSize,
                        pointCount);
            }
            if (payload.externInputCount() == 0) {
                for (int ignored = 0; ignored < roots.length; ignored++) {
                    bts$advanceFillSliceProviderState(columns, yCount);
                }
            }
            RouterPipeline.recordGpuPayloadBatchTimings(
                    externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart,
                    attempt.preparedLauncherCacheHit(), attempt.preparedLauncherInvoked());
            RouterPipeline.recordGpuPayloadBatchPreparedTimings(attempt.preparedInvocationTimings());
            RouterPipeline.recordGpuPayloadBatchGpuSuccess(combinedPointCount);
            NoiseChunkTimingStats.recordFillSliceGpuGroupLaunch(roots.length, combinedPointCount);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            RouterPipeline.recordGpuPayloadBatchTimings(
                    externNanos, invokeNanos, parityNanos, System.nanoTime() - totalStart);
            GpuPayloadBatchExecutor.disableGpuForLifecycle(exception.toString());
            RouterPipeline.recordGpuPayloadBatchCpuFallback(combinedPointCount, exception.toString());
            return false;
        }
    }

    @Unique
    private GpuPayloadBatchExecutor.RuntimeParityReport bts$fillSliceRuntimeParity(
            CompiledDensityFunction[] roots,
            GpuIrPayload payload,
            int columns,
            int yCount,
            int pointCount,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues,
            double[] gpuOutput,
            double[] expected,
            double[] payloadExpected,
            long arrayCounterBase,
            int combinedPointCount) {
        if ((payload.hasExternInputs() || payload.requiresRootParity())
                && GpuPayloadBatchExecutor.runtimeParityRemaining() > 0) {
            for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
                bts$fillSliceCpuExpected(
                        roots[rootIndex], columns, yCount, expected,
                        rootIndex * pointCount, arrayCounterBase);
            }
            return GpuPayloadBatchExecutor.checkRuntimeParityAgainstRootExpected(
                    payload, blockX, blockY, blockZ, externValues, gpuOutput,
                    expected, payloadExpected, combinedPointCount);
        }
        return GpuPayloadBatchExecutor.checkRuntimeParity(
                payload, blockX, blockY, blockZ, externValues, gpuOutput, expected);
    }

    @Unique
    private static int bts$fillSliceLaunchPointCount(int pointCount) {
        return pointCount;
    }

    @Unique
    private static void bts$clearFillSlicePadding(
            int pointCount,
            int launchPointCount,
            int externInputCount,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            double[] externValues) {
        if (launchPointCount <= pointCount) {
            return;
        }
        java.util.Arrays.fill(blockX, pointCount, launchPointCount, 0);
        java.util.Arrays.fill(blockY, pointCount, launchPointCount, 0);
        java.util.Arrays.fill(blockZ, pointCount, launchPointCount, 0);
        if (externInputCount > 0) {
            java.util.Arrays.fill(externValues,
                    pointCount * externInputCount,
                    launchPointCount * externInputCount,
                    0.0D);
        }
    }

    @Unique
    private void bts$fillSliceCoordinates(
            int columns,
            int yCount,
            int[] blockX,
            int[] blockY,
            int[] blockZ,
            int pointOffset) {
        int idx = pointOffset;
        final int startX = this.cellStartBlockX;
        for (int column = 0; column < columns; column++) {
            int startZ = (this.firstCellZ + column) * this.cellWidth;
            for (int y = 0; y < yCount; y++) {
                blockX[idx] = startX;
                blockY[idx] = (y + this.cellNoiseMinY) * this.cellHeight;
                blockZ[idx] = startZ;
                idx++;
            }
        }
    }

    @Unique
    private boolean bts$fillSliceExternInputValues(
            GpuIrPayload payload,
            CompiledDensityFunction root,
            int columns,
            int yCount,
            double[] externValues,
            int pointOffset,
            int externStride,
            long arrayCounterBase) {
        int externInputCount = payload.externInputCount();
        if (externInputCount == 0) {
            return true;
        }
        DensityFunction[] inputFunctions = bts$resolveExternInputFunctions(payload, root);
        if (inputFunctions == null) {
            return false;
        }
        int[] duplicateExternSlots = bts$duplicateFillSliceExternSlots(inputFunctions);
        int idx = 0;
        NoiseChunk self = (NoiseChunk) (Object) this;
        long restoreArrayCounter = this.arrayInterpolationCounter;
        long externInputStart = NoiseChunkTimingStats.ENABLED ? System.nanoTime() : 0L;
        boolean reuseYInvariantDirectReads = bts$fillSliceExternYInvariantReuseEnabled();
        boolean[] yInvariantDirectRead = reuseYInvariantDirectReads ? new boolean[externInputCount] : null;
        double[] yInvariantValues = reuseYInvariantDirectReads ? new double[externInputCount] : null;
        boolean trackExternInputStats = NoiseChunkTimingStats.ENABLED;
        String[] externInputLabels = bts$fillSliceExternInputLabels(inputFunctions);
        String[] externInputDetails = trackExternInputStats
                ? bts$fillSliceExternInputDetails(payload, root)
                : null;
        long[] directHitsBySlot = trackExternInputStats ? new long[externInputCount] : null;
        long[] directMissesBySlot = trackExternInputStats ? new long[externInputCount] : null;
        long[] computesBySlot = trackExternInputStats ? new long[externInputCount] : null;
        boolean[] directReadProbeBySlot = bts$fillSliceDirectReadProbeFlags(inputFunctions, externInputLabels);
        int directReadMissDisableThreshold = bts$fillSliceDirectReadMissDisableThreshold();
        int[] directReadMissStreakBySlot = directReadMissDisableThreshold > 0
                ? new int[externInputCount]
                : null;
        long directAttempts = 0L;
        long directHits = 0L;
        long directMisses = 0L;
        long computes = 0L;
        long wrappedComputes = 0L;
        try {
            for (int column = 0; column < columns; column++) {
                this.arrayInterpolationCounter = arrayCounterBase + column + 1L;
                this.cellStartBlockZ = (this.firstCellZ + column) * this.cellWidth;
                this.inCellZ = 0;
                if (reuseYInvariantDirectReads) {
                    Arrays.fill(yInvariantDirectRead, false);
                }
                for (int y = 0; y < yCount; y++) {
                    bts$setFillSliceProviderPoint(y);
                    int base = (pointOffset + idx) * externStride;
                    for (int slot = 0; slot < externInputCount; slot++) {
                        int duplicateOf = duplicateExternSlots[slot];
                        if (duplicateOf >= 0) {
                            externValues[base + slot] = externValues[base + duplicateOf];
                            continue;
                        }
                        if (reuseYInvariantDirectReads && yInvariantDirectRead[slot]) {
                            externValues[base + slot] = yInvariantValues[slot];
                            continue;
                        }
                        DensityFunction input = inputFunctions[slot];
                        if (reuseYInvariantDirectReads
                                && y == 0
                                && bts$isFillSliceExternYInvariantCache(input)) {
                            double direct = bts$tryFillSliceDirectRead(input, self);
                            directAttempts++;
                            if (Double.doubleToRawLongBits(direct) != DfcCacheFastPath.MISS_BITS) {
                                directHits++;
                                if (directHitsBySlot != null) {
                                    directHitsBySlot[slot]++;
                                }
                                if (directReadMissStreakBySlot != null) {
                                    directReadMissStreakBySlot[slot] = 0;
                                }
                                yInvariantDirectRead[slot] = true;
                                yInvariantValues[slot] = direct;
                                externValues[base + slot] = direct;
                                continue;
                            }
                            directMisses++;
                            if (directMissesBySlot != null) {
                                directMissesBySlot[slot]++;
                            }
                        }
                        if (directReadProbeBySlot[slot]
                                && input instanceof DfcCellCacheAccess access) {
                            double direct = access.dfc$tryDirectRead(self);
                            directAttempts++;
                            if (Double.doubleToRawLongBits(direct) != DfcCacheFastPath.MISS_BITS) {
                                directHits++;
                                if (directHitsBySlot != null) {
                                    directHitsBySlot[slot]++;
                                }
                                if (directReadMissStreakBySlot != null) {
                                    directReadMissStreakBySlot[slot] = 0;
                                }
                                externValues[base + slot] = direct;
                                continue;
                            }
                            directMisses++;
                            if (directMissesBySlot != null) {
                                directMissesBySlot[slot]++;
                            }
                            bts$recordFillSliceDirectReadMiss(
                                    directReadProbeBySlot,
                                    directReadMissStreakBySlot,
                                    externInputLabels,
                                    slot,
                                    directReadMissDisableThreshold);
                        }
                        computes++;
                        if (computesBySlot != null) {
                            computesBySlot[slot]++;
                        }
                        if (bts$fillSliceCacheOnceWrappedComputeEnabled()
                                && !directReadProbeBySlot[slot]
                                && input instanceof DfcCacheOnceWrappedAccess wrappedAccess) {
                            wrappedComputes++;
                            externValues[base + slot] = wrappedAccess.dfc$computeWrapped(self);
                        } else {
                            externValues[base + slot] = input.compute(self);
                        }
                    }
                    idx++;
                }
            }
        } finally {
            this.arrayInterpolationCounter = restoreArrayCounter;
            if (externInputStart != 0L) {
                NoiseChunkTimingStats.recordFillSliceExternInputNanos(System.nanoTime() - externInputStart);
                NoiseChunkTimingStats.recordFillSliceExternInputStats(
                        directAttempts, directHits, directMisses, computes, wrappedComputes);
                if (trackExternInputStats) {
                    for (int slot = 0; slot < externInputLabels.length; slot++) {
                        NoiseChunkTimingStats.recordFillSliceExternInputClassStats(
                                externInputLabels[slot],
                                externInputDetails == null ? null : externInputDetails[slot],
                                directHitsBySlot[slot],
                                directMissesBySlot[slot],
                                computesBySlot[slot]);
                    }
                }
            }
        }
        return true;
    }

    @Unique
    private static boolean[] bts$fillSliceDirectReadProbeFlags(
            DensityFunction[] inputFunctions,
            String[] externInputLabels) {
        boolean[] flags = new boolean[inputFunctions.length];
        for (int i = 0; i < inputFunctions.length; i++) {
            flags[i] = bts$shouldProbeFillSliceDirectRead(
                    inputFunctions[i],
                    externInputLabels == null ? null : externInputLabels[i]);
        }
        return flags;
    }

    @Unique
    private static boolean bts$shouldProbeFillSliceDirectRead(DensityFunction input, String label) {
        if (input instanceof DensityFunctions.MarkerOrMarked marked
                && marked.type() == DensityFunctions.Marker.Type.CacheOnce
                && marked.wrapped() instanceof DensityFunctions.MulOrAdd) {
            return false;
        }
        if (label != null
                && label.contains("Marker:CacheOnce->net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd")) {
            return false;
        }
        return true;
    }

    @Unique
    private static int bts$fillSliceDirectReadMissDisableThreshold() {
        return Math.max(0, Integer.getInteger(
                "ga.dfc.gpu.fillSliceDirectReadMissDisableThreshold",
                1));
    }

    @Unique
    private static boolean bts$fillSliceCacheOnceWrappedComputeEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "ga.dfc.gpu.fillSliceCacheOnceWrappedCompute",
                "true"));
    }

    @Unique
    private static void bts$recordFillSliceDirectReadMiss(
            boolean[] directReadProbeBySlot,
            int[] directReadMissStreakBySlot,
            String[] externInputLabels,
            int slot,
            int threshold) {
        if (threshold <= 0
                || directReadMissStreakBySlot == null
                || !bts$canDisableFillSliceDirectReadAfterMisses(externInputLabels[slot])) {
            return;
        }
        int misses = ++directReadMissStreakBySlot[slot];
        if (misses >= threshold) {
            directReadProbeBySlot[slot] = false;
        }
    }

    @Unique
    private static boolean bts$canDisableFillSliceDirectReadAfterMisses(String label) {
        return label != null && label.startsWith("Marker:CacheOnce->");
    }

    @Unique
    private static int[] bts$duplicateFillSliceExternSlots(DensityFunction[] inputFunctions) {
        int[] duplicateOf = new int[inputFunctions.length];
        Arrays.fill(duplicateOf, -1);
        for (int slot = 0; slot < inputFunctions.length; slot++) {
            DensityFunction input = inputFunctions[slot];
            for (int prior = 0; prior < slot; prior++) {
                if (inputFunctions[prior] == input) {
                    duplicateOf[slot] = prior;
                    break;
                }
            }
        }
        return duplicateOf;
    }

    @Unique
    private static String[] bts$fillSliceExternInputLabels(DensityFunction[] inputFunctions) {
        String[] labels = new String[inputFunctions.length];
        for (int i = 0; i < inputFunctions.length; i++) {
            labels[i] = bts$describeFillSlicePayloadExternFunction(inputFunctions[i]);
        }
        return labels;
    }

    @Unique
    private static String[] bts$fillSliceExternInputDetails(
            GpuIrPayload payload,
            CompiledDensityFunction rootOwner) {
        int count = payload.externInputCount();
        String[] details = new String[count];
        for (int slot = 0; slot < count; slot++) {
            details[slot] = bts$describeFillSlicePayloadExternSlot(payload, rootOwner, slot);
        }
        return details;
    }

    @Unique
    private static boolean bts$fillSliceExternYInvariantReuseEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "ga.dfc.gpu.fillSliceExternYInvariantReuse",
                "true"));
    }

    @Unique
    private static boolean bts$isFillSliceExternYInvariantCache(DensityFunction input) {
        if (!(input instanceof DensityFunctions.MarkerOrMarked marked)) {
            return bts$isFillSliceExternYInvariantWrapped(input);
        }
        return marked.type() == DensityFunctions.Marker.Type.FlatCache
                || marked.type() == DensityFunctions.Marker.Type.Cache2D;
    }

    @Unique
    private static final Map<DensityFunction, Boolean> bts$fillSliceExternYInvariantWrappedCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Unique
    private static boolean bts$isFillSliceExternYInvariantWrapped(DensityFunction input) {
        if (!(input instanceof DfcCacheOnceWrappedAccess wrappedAccess)) {
            return false;
        }
        return bts$fillSliceExternYInvariantWrappedCache.computeIfAbsent(input, ignored -> {
            DensityFunction wrapped = wrappedAccess.dfc$wrappedFunction();
            if (wrapped == null) {
                return false;
            }
            try {
                return bts$isFillSliceExternYInvariantFunction(wrapped, new IdentityHashMap<>());
            } catch (RuntimeException | LinkageError ignoredException) {
                return false;
            }
        });
    }

    @Unique
    private static boolean bts$isFillSliceExternYInvariantFunction(
            DensityFunction input,
            IdentityHashMap<DensityFunction, Boolean> visiting) {
        if (input == null) {
            return false;
        }
        if (visiting.put(input, Boolean.TRUE) != null) {
            return false;
        }
        if (input instanceof DensityFunctions.Constant) {
            return true;
        }
        if (input instanceof DensityFunctions.MarkerOrMarked marked) {
            if (marked.type() == DensityFunctions.Marker.Type.FlatCache
                    || marked.type() == DensityFunctions.Marker.Type.Cache2D) {
                return true;
            }
            if (marked.type() == DensityFunctions.Marker.Type.CacheOnce
                    && input instanceof DfcCacheOnceWrappedAccess wrappedAccess) {
                return bts$isFillSliceExternYInvariantFunction(
                        wrappedAccess.dfc$wrappedFunction(), visiting);
            }
            return false;
        }
        if (input instanceof DensityFunctions.MulOrAdd ma) {
            return bts$isFillSliceExternYInvariantFunction(ma.input(), visiting);
        }
        if (input instanceof DensityFunctions.TwoArgumentSimpleFunction fn) {
            return bts$isFillSliceExternYInvariantFunction(fn.argument1(), visiting)
                    && bts$isFillSliceExternYInvariantFunction(fn.argument2(), visiting);
        }
        if (input instanceof DensityFunctions.Mapped mapped) {
            return bts$isFillSliceExternYInvariantFunction(mapped.input(), visiting);
        }
        if (input instanceof DensityFunctions.Clamp clamp) {
            return bts$isFillSliceExternYInvariantFunction(clamp.input(), visiting);
        }
        if (input instanceof DensityFunctions.RangeChoice rangeChoice) {
            return bts$isFillSliceExternYInvariantFunction(rangeChoice.input(), visiting)
                    && bts$isFillSliceExternYInvariantFunction(rangeChoice.whenInRange(), visiting)
                    && bts$isFillSliceExternYInvariantFunction(rangeChoice.whenOutOfRange(), visiting);
        }
        if (input instanceof DensityFunctions.HolderHolder holder) {
            return bts$isFillSliceExternYInvariantFunction(holder.function().value(), visiting);
        }
        return false;
    }

    @Unique
    private static double bts$tryFillSliceDirectRead(
            DensityFunction input,
            DensityFunction.FunctionContext context) {
        if (input instanceof DfcCellCacheAccess access) {
            return access.dfc$tryDirectRead(context);
        }
        return DfcCacheFastPath.CACHE_MISS;
    }

    @Unique
    private void bts$fillSliceCpuExpected(
            CompiledDensityFunction root,
            int columns,
            int yCount,
            double[] expected,
            int pointOffset,
            long arrayCounterBase) {
        int idx = pointOffset;
        NoiseChunk self = (NoiseChunk) (Object) this;
        long restoreArrayCounter = this.arrayInterpolationCounter;
        try {
            for (int column = 0; column < columns; column++) {
                this.arrayInterpolationCounter = arrayCounterBase + column + 1L;
                this.cellStartBlockZ = (this.firstCellZ + column) * this.cellWidth;
                this.inCellZ = 0;
                for (int y = 0; y < yCount; y++) {
                    bts$setFillSliceProviderPoint(y);
                    expected[idx++] = root.compute(self);
                }
            }
        } finally {
            this.arrayInterpolationCounter = restoreArrayCounter;
        }
    }

    @Unique
    private void bts$advanceFillSliceProviderState(int columns, int yCount) {
        for (int column = 0; column < columns; column++) {
            this.cellStartBlockZ = (this.firstCellZ + column) * this.cellWidth;
            this.inCellZ = 0;
            for (int y = 0; y < yCount; y++) {
                bts$setFillSliceProviderPoint(y);
            }
        }
    }

    @Unique
    private void bts$setFillSliceProviderPoint(int y) {
        this.cellStartBlockY = (y + this.cellNoiseMinY) * this.cellHeight;
        ++this.interpolationCounter;
        this.inCellX = 0;
        this.inCellY = 0;
        this.arrayIndex = y;
    }

    @Unique
    private static DensityFunction[] bts$resolveExternInputFunctions(
            GpuIrPayload payload,
            CompiledDensityFunction rootOwner) {
        int count = payload.externInputCount();
        DensityFunction[] inputFunctions = new DensityFunction[count];
        for (int slot = 0; slot < count; slot++) {
            CompiledDensityFunction owner = rootOwner;
            int pathOffset = payload.externInputPathOffsets()[slot];
            int pathLength = payload.externInputPathLengths()[slot];
            for (int i = 0; i < pathLength; i++) {
                DensityFunction next = owner.dfc$extern(payload.externInputOwnerPath()[pathOffset + i]);
                if (!(next instanceof CompiledDensityFunction compiled)) {
                    return null;
                }
                owner = compiled;
            }
            DensityFunction input = owner.dfc$extern(payload.externInputLeafExternIndices()[slot]);
            if (input == null) {
                return null;
            }
            inputFunctions[slot] = input;
        }
        return inputFunctions;
    }

    @Unique
    private static DensityFunction bts$fillSliceRoot(NoiseChunk.NoiseInterpolator interpolator) {
        return interpolator instanceof DensityFunctions.MarkerOrMarked marked
                ? marked.wrapped()
                : interpolator;
    }

    @Unique
    private void bts$recordFillSlicePayloadSurface(
            int columns,
            int yCount,
            NoiseChunk.NoiseInterpolator[] interpolators) {
        if (!NoiseChunkTimingStats.ENABLED
                || !bts$fillSliceGpuCollectionAvailable()
                || columns <= 0
                || yCount <= 0
                || interpolators.length == 0) {
            return;
        }
        if (this.bts$fillSliceCompiledRoots == null) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("payload-surface-compiled-roots-null");
            return;
        }
        if (this.bts$fillSliceGpuPayloads == null) {
            NoiseChunkTimingStats.recordFillSliceGpuCollectionSkip("payload-surface-payload-array-null");
            return;
        }
        long pointsPerRoot;
        try {
            pointsPerRoot = Math.multiplyExact((long) columns, yCount);
        } catch (ArithmeticException ignored) {
            pointsPerRoot = Long.MAX_VALUE;
        }
        for (int i = 0; i < interpolators.length; i++) {
            NoiseChunk.NoiseInterpolator interpolator = interpolators[i];
            CompiledDensityFunction compiledRoot = this.bts$fillSliceCompiledRoots[i];
            DensityFunction root = compiledRoot != null ? compiledRoot : bts$fillSliceRoot(interpolator);
            GpuIrPayload payload = this.bts$fillSliceGpuPayloads[i];
            GpuPayloadRuntimeRegistry.Diagnostics diagnostics = null;
            if (compiledRoot != null && payload == null) {
                diagnostics = GpuPayloadRuntimeRegistry.diagnostics(compiledRoot);
            }
            NoiseChunkTimingStats.recordFillSlicePayloadRoot(
                    payload != null,
                    payload != null && payload.hasExternInputs(),
                    pointsPerRoot,
                    root,
                    bts$describeFillSlicePayloadBlocker(root, payload, diagnostics));
            if (payload != null && payload.hasExternInputs() && compiledRoot != null) {
                bts$recordFillSlicePayloadExternLeaves(payload, compiledRoot);
            }
        }
    }

    @Unique
    private static void bts$recordFillSlicePayloadExternLeaves(
            GpuIrPayload payload,
            CompiledDensityFunction rootOwner) {
        int count = payload.externInputCount();
        for (int slot = 0; slot < count; slot++) {
            NoiseChunkTimingStats.recordFillSlicePayloadExternLeaf(
                    bts$describeFillSlicePayloadExternLeaf(payload, rootOwner, slot));
        }
    }

    @Unique
    private static String bts$describeFillSlicePayloadExternLeaf(
            GpuIrPayload payload,
            CompiledDensityFunction rootOwner,
            int slot) {
        CompiledDensityFunction owner = rootOwner;
        int pathOffset = payload.externInputPathOffsets()[slot];
        int pathLength = payload.externInputPathLengths()[slot];
        for (int i = 0; i < pathLength; i++) {
            DensityFunction next = owner.dfc$extern(payload.externInputOwnerPath()[pathOffset + i]);
            if (!(next instanceof CompiledDensityFunction compiled)) {
                return "path:" + bts$describeFillSlicePayloadExternFunction(next);
            }
            owner = compiled;
        }
        return bts$describeFillSlicePayloadExternFunction(
                owner.dfc$extern(payload.externInputLeafExternIndices()[slot]));
    }

    @Unique
    private static String bts$describeFillSlicePayloadExternSlot(
            GpuIrPayload payload,
            CompiledDensityFunction rootOwner,
            int slot) {
        StringBuilder path = new StringBuilder();
        int pathOffset = payload.externInputPathOffsets()[slot];
        int pathLength = payload.externInputPathLengths()[slot];
        for (int i = 0; i < pathLength; i++) {
            if (i > 0) {
                path.append('/');
            }
            path.append(payload.externInputOwnerPath()[pathOffset + i]);
        }
        return "slot=" + slot
                + ",path=" + path
                + ",leaf=" + payload.externInputLeafExternIndices()[slot]
                + ",fn=" + bts$describeFillSlicePayloadExternLeaf(payload, rootOwner, slot);
    }

    @Unique
    private static String bts$describeFillSlicePayloadExternFunction(DensityFunction function) {
        return bts$describeFillSlicePayloadExternFunction(function, new IdentityHashMap<>(), 0);
    }

    @Unique
    private static String bts$describeFillSlicePayloadExternFunction(
            DensityFunction function,
            IdentityHashMap<DensityFunction, Boolean> seen,
            int depth) {
        if (function == null) {
            return "null";
        }
        if (depth >= 8) {
            return function.getClass().getName() + "->...";
        }
        if (seen.put(function, Boolean.TRUE) != null) {
            return function.getClass().getName() + "->cycle";
        }
        if (function instanceof DensityFunctions.MarkerOrMarked marked) {
            DensityFunction wrapped = marked.wrapped();
            return "Marker:" + marked.type() + "->"
                    + bts$describeFillSlicePayloadExternFunction(wrapped, seen, depth + 1);
        }
        return function.getClass().getName();
    }

    @Unique
    private static String bts$describeFillSlicePayloadBlocker(
            DensityFunction root,
            GpuIrPayload payload,
            GpuPayloadRuntimeRegistry.Diagnostics diagnostics) {
        if (payload != null || !(root instanceof CompiledDensityFunction)) {
            return null;
        }
        if (diagnostics == null) {
            return "compiled:diagnostics-missing";
        }
        String reason = bts$firstNonNone(
                diagnostics.firstUnsupportedDetail(),
                diagnostics.firstUnsupportedNode(),
                diagnostics.firstEligibilityBlocker());
        if (reason == null) {
            if (!diagnostics.eligibilityBlockers().isEmpty()) {
                reason = diagnostics.eligibilityBlockers().get(0);
            } else {
                reason = "unknown";
            }
        }
        return "compiled:" + reason;
    }

    @Unique
    private static String bts$firstNonNone(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"none".equals(value) && !"unknown".equals(value)) {
                return value;
            }
        }
        return null;
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
