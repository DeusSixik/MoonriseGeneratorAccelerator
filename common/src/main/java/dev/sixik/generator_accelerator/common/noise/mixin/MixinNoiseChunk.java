package dev.sixik.generator_accelerator.common.noise.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferColumnBandNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferPlan;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferPrimitiveAccess;
import dev.sixik.generator_accelerator.common.noise.CachedPointContext;
import dev.sixik.generator_accelerator.common.noise.GAFusedTerrainNoiseChunkAccess;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$InterpolatorSoA;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$NoiseInterpolatorPatch;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkPatch;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkSliceProvider;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheCompiledFillerAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillParity;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunk implements NoiseChunkPatch, NoiseChunk$InterpolatorSoA, GAFusedTerrainNoiseChunkAccess {

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
    private Aquifer aquifer;
    @Shadow
    @Final
    private NoiseChunk.BlockStateFiller blockStateRule;
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
    private boolean[] bts$cellCacheFastDisabled;
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
    private DensityFunction ga$terrainSubstanceDensity;
    @Unique
    private NoiseChunk.BlockStateFiller ga$oreVeinRule;
    @Unique
    private boolean ga$fusedTerrainAvailable;
    @Unique
    private GAAquiferColumnBandNearest[] ga$terrainColumnBands;
    @Unique
    private GAAquiferNearest ga$terrainNearestScratch;
    @Unique
    private boolean ga$fusedTerrainParityLogged;

    @Unique
    private static final boolean GA$FUSED_TERRAIN_ENABLED = !"false".equalsIgnoreCase(System.getProperty(
            "ga.aquifer.fusedTerrain.enabled",
            "true"
    ));
    @Unique
    private static final boolean GA$FUSED_TERRAIN_PARITY_CHECK = Boolean.parseBoolean(System.getProperty(
            "ga.aquifer.fusedTerrain.parityCheck",
            "false"
    ));
    @Unique
    private static final int GA$FUSED_TERRAIN_PARITY_MASK = Integer.getInteger(
            "ga.aquifer.fusedTerrain.parityMask",
            1023
    );
    @Unique
    private static final Set<String> GA$BROKEN_CELL_FILLER_CLASSES = ConcurrentHashMap.newKeySet();

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

    @Inject(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;blockStateRule:Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            )
    )
    private void ga$initFusedTerrainAccess(
            int cellCountXZ,
            RandomState randomState,
            int firstBlockX,
            int firstBlockZ,
            NoiseSettings noiseSettings,
            DensityFunctions.BeardifierOrMarker beardifierOrMarker,
            NoiseGeneratorSettings noiseGeneratorSettings,
            Aquifer.FluidPicker fluidPicker,
            Blender blender,
            CallbackInfo ci,
            @Local(ordinal = 0) DensityFunction terrainSubstanceDensity
    ) {
        if (!GA$FUSED_TERRAIN_ENABLED) {
            return;
        }
        if (!(this.blockStateRule instanceof MaterialRuleList materialRuleList)) {
            return;
        }
        int ruleCount = materialRuleList.materialRuleList().size();
        if (ruleCount != 1 && ruleCount != 2) {
            return;
        }
        this.ga$terrainSubstanceDensity = terrainSubstanceDensity;
        this.ga$oreVeinRule = ruleCount > 1 ? materialRuleList.materialRuleList().get(1) : null;
        this.ga$fusedTerrainAvailable = true;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bts$initOptimizationFields(CallbackInfo ci) {
        this.sliceFillingContextProvider = new NoiseChunkSliceProvider((NoiseChunk)(Object)this);

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

        int size = this.noiseSizeXZ + 1;
        this.surfaceCache = new int[size * size];
        Arrays.fill(this.surfaceCache, Integer.MIN_VALUE);
        this.reusableContext = new CachedPointContext();

        this.sliceBuffer = new double[this.cellCountY + 1];
        this.ga$terrainColumnBands = new GAAquiferColumnBandNearest[256];
        this.ga$terrainNearestScratch = new GAAquiferNearest();
    }

    @Override
    public boolean ga$fusedTerrainAvailable() {
        return this.ga$fusedTerrainAvailable && this.ga$terrainSubstanceDensity != null;
    }

    @Override
    public int ga$sampleFusedTerrainBlockId(int defaultBlockId) {
        NoiseChunk self = (NoiseChunk) (Object) this;
        long packed = ga$sampleFusedTerrainPackedBlockId(defaultBlockId, self.blockX(), self.blockY(), self.blockZ());
        return GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed)
                ? GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_BLOCK_ID
                : GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packed);
    }

    @Override
    public int ga$sampleFusedTerrainBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        long packed = ga$sampleFusedTerrainPackedBlockId(defaultBlockId, blockX, blockY, blockZ);
        return GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packed)
                ? GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_BLOCK_ID
                : GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packed);
    }

    @Override
    public long ga$sampleFusedTerrainPackedBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        if (!ga$fusedTerrainAvailable()) {
            return GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_PACKED_BLOCK_ID;
        }
        NoiseChunk self = (NoiseChunk) (Object) this;
        double density = this.ga$terrainSubstanceDensity.compute(self);
        Aquifer aquifer = this.aquifer;
        boolean scheduleFluidUpdate;
        if (aquifer instanceof GAAquiferPrimitiveAccess primitiveAccess) {
            int blockId = primitiveAccess.ga$computeSubstanceIdAt(
                    self,
                    density,
                    blockX,
                    blockY,
                    blockZ,
                    this.ga$terrainColumnBand(blockX, blockZ),
                    this.ga$terrainNearestScratch
            );
            if (blockId == GAAquiferPrimitiveAccess.GA_FALLBACK_RESULT) {
                return GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_PACKED_BLOCK_ID;
            }
            scheduleFluidUpdate = primitiveAccess.ga$lastShouldScheduleFluidUpdate();
            if (blockId != GAAquiferPlan.SOLID_RESULT) {
                return this.ga$checkedFusedTerrainPackedBlockId(self, defaultBlockId, blockId, scheduleFluidUpdate);
            }
        } else {
            BlockState aquiferState = aquifer.computeSubstance(self, density);
            scheduleFluidUpdate = aquifer.shouldScheduleFluidUpdate();
            if (aquiferState != null) {
                int blockId = GA$BlockStateExtension.get(aquiferState).bts$getFastId();
                return this.ga$checkedFusedTerrainPackedBlockId(self, defaultBlockId, blockId, scheduleFluidUpdate);
            }
        }

        BlockState oreState = this.ga$oreVeinRule == null ? null : this.ga$oreVeinRule.calculate(self);
        int blockId = oreState == null ? defaultBlockId : GA$BlockStateExtension.get(oreState).bts$getFastId();
        return this.ga$checkedFusedTerrainPackedBlockId(
                self,
                defaultBlockId,
                blockId,
                aquifer.shouldScheduleFluidUpdate()
        );
    }

    @Override
    public BlockState ga$sampleFusedTerrainBlockState(BlockState defaultBlock) {
        int defaultBlockId = GA$BlockStateExtension.get(defaultBlock).bts$getFastId();
        int blockId = ga$sampleFusedTerrainBlockId(defaultBlockId);
        if (blockId == GAFusedTerrainNoiseChunkAccess.GA_FALLBACK_BLOCK_ID) {
            return null;
        }
        return blockId == defaultBlockId ? defaultBlock : FastBlockStateCache.getBlockState(blockId);
    }

    @Unique
    private GAAquiferColumnBandNearest ga$terrainColumnBand(int blockX, int blockZ) {
        int index = ((blockX & 15) << 4) | (blockZ & 15);
        GAAquiferColumnBandNearest band = this.ga$terrainColumnBands[index];
        if (band == null) {
            band = new GAAquiferColumnBandNearest();
            this.ga$terrainColumnBands[index] = band;
        }
        return band;
    }

    @Unique
    private long ga$checkedFusedTerrainPackedBlockId(
            NoiseChunk self,
            int defaultBlockId,
            int blockId,
            boolean fusedScheduleFluidUpdate
    ) {
        if (!GA$FUSED_TERRAIN_PARITY_CHECK) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(blockId, fusedScheduleFluidUpdate);
        }
        int mask = GA$FUSED_TERRAIN_PARITY_MASK;
        if (mask > 0 && (((int) this.interpolationCounter) & mask) != 0) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(blockId, fusedScheduleFluidUpdate);
        }
        BlockState vanillaState = this.blockStateRule.calculate(self);
        boolean vanillaScheduleFluidUpdate = this.aquifer.shouldScheduleFluidUpdate();
        int vanillaBlockId = vanillaState == null
                ? defaultBlockId
                : GA$BlockStateExtension.get(vanillaState).bts$getFastId();
        boolean scheduleRelevant = !FastBlockStateCache.isFluidEmpty(blockId)
                || !FastBlockStateCache.isFluidEmpty(vanillaBlockId);
        if (vanillaBlockId == blockId && (!scheduleRelevant || vanillaScheduleFluidUpdate == fusedScheduleFluidUpdate)) {
            return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(blockId, fusedScheduleFluidUpdate);
        }
        this.ga$fusedTerrainAvailable = false;
        if (!this.ga$fusedTerrainParityLogged) {
            this.ga$fusedTerrainParityLogged = true;
            GeneratorAccelerator.LOGGER.warn(
                    "GA fused terrain parity mismatch at {},{},{}: fused={}/schedule={}, vanilla={}/schedule={}; disabling fused terrain for this NoiseChunk",
                    self.blockX(),
                    self.blockY(),
                    self.blockZ(),
                    blockId,
                    fusedScheduleFluidUpdate,
                    vanillaBlockId,
                    vanillaScheduleFluidUpdate
            );
        }
        return GAFusedTerrainNoiseChunkAccess.ga$packFusedTerrain(vanillaBlockId, vanillaScheduleFluidUpdate);
    }

    @Unique
    private void bts$initCellCacheArrays() {
        final NoiseChunk.CacheAllInCell[] caches = this.bts$cellCachesArray;
        final int length = caches.length;
        this.bts$cellCacheFillers = new DensityFunction[length];
        this.bts$cellCacheFastFillers = new DfcCellFillAccess[length];
        this.bts$cellCacheLazyFastFillers = new boolean[length];
        this.bts$cellCacheFastDisabled = new boolean[length];
        this.bts$cellCacheValues = new double[length][];

        for (int i = 0; i < length; i++) {
            final NoiseChunk.CacheAllInCell cache = caches[i];
            final DensityFunction filler = cache.noiseFiller;
            this.bts$cellCacheFillers[i] = filler;
            this.bts$cellCacheValues[i] = cache.values;
            if (filler instanceof DfcCellFillAccess access) {
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
            NoiseChunk$NoiseInterpolatorPatch patch = (NoiseChunk$NoiseInterpolatorPatch) array[i];
            patch.bts$setSoAIndex(i);
        }
    }

    /**
     * @author Sixik
     * @reason Optimize List iteration -> Array iteration
     */
    @Overwrite
    public void selectCellYZ(int yIndex, int zIndex) {
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
        final boolean[] disabledFastFillers = this.bts$cellCacheFastDisabled;
        final double[][] valuesArray = this.bts$cellCacheValues;
        for (int i = 0; i < fillers.length; i++) {
            final DensityFunction filler = fillers[i];
            DfcCellFillAccess fast = disabledFastFillers[i] ? null : fastFillers[i];

            if (fast == null && !disabledFastFillers[i] && caches[i] instanceof DfcCellCacheCompiledFillerAccess access) {
                fast = access.dfc$getOrCompileCellFiller();
                if (fast != null) {
                    if (ga$isBrokenCellFiller(fast)) {
                        disabledFastFillers[i] = true;
                        fast = null;
                    } else {
                        fastFillers[i] = fast;
                        lazyFastFillers[i] = true;
                    }
                }
            }
            if (fast != null && ga$isBrokenCellFiller(fast)) {
                disabledFastFillers[i] = true;
                fastFillers[i] = null;
                fast = null;
            }

            final double[] values = valuesArray[i];
            if (fast != null) {
                if (DfcCellFillStats.ENABLED) {
                    DfcCellFillStats.recordCellFill(fast, filler);
                }
                try {
                    fast.dfc$fillCell(values, self);
                    if (DfcCellFillParity.isActive()) {
                        DfcCellFillParity.recordCandidate(filler, true, lazyFastFillers[i]);
                        DfcCellFillParity.check(filler, values, self);
                    }
                } catch (ArrayIndexOutOfBoundsException exception) {
                    disabledFastFillers[i] = true;
                    fastFillers[i] = null;
                    ga$rememberBrokenCellFiller(fast, filler, exception);
                    ga$fillCellVanilla(filler, values, self, exception);
                }
            } else {
                if (DfcCellFillParity.isActive()) {
                    DfcCellFillParity.recordCandidate(filler, false, false);
                }
                ga$fillCellVanilla(filler, values, self, null);
            }
        }

        ++this.arrayInterpolationCounter;
        this.fillingCell = false;
    }

    @Unique
    private static boolean ga$isBrokenCellFiller(DfcCellFillAccess filler) {
        return GA$BROKEN_CELL_FILLER_CLASSES.contains(filler.getClass().getName());
    }

    @Unique
    private static void ga$rememberBrokenCellFiller(
            DfcCellFillAccess fast,
            DensityFunction source,
            ArrayIndexOutOfBoundsException exception
    ) {
        String className = fast.getClass().getName();
        if (!GA$BROKEN_CELL_FILLER_CLASSES.add(className)) {
            return;
        }
        String debugState = fast instanceof CompiledDensityFunction compiled
                ? compiled.dfc$debugState()
                : fast.getClass().getName();
        GeneratorAccelerator.LOGGER.warn(
                "DFC cell-fill disabled after bounds failure; falling back to vanilla fillArray. source={}, fast={}",
                source.getClass().getName(),
                debugState,
                exception
        );
    }

    @Unique
    private static void ga$fillCellVanilla(
            DensityFunction filler,
            double[] values,
            NoiseChunk self,
            ArrayIndexOutOfBoundsException fastFailure
    ) {
        try {
            filler.fillArray(values, self);
        } catch (Throwable fallbackFailure) {
            if (fastFailure != null) {
                fastFailure.addSuppressed(fallbackFailure);
                throw fastFailure;
            }
            if (fallbackFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (fallbackFailure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(fallbackFailure);
        }
    }

    /**
     * @author Sixik
     * @reason Array iteration
     */
    @Overwrite
    public void updateForY(int blockY, double delta) {
        this.inCellY = blockY - this.cellStartBlockY;

        final double[] noise000 = this.bts$noise000;
        for (int i = 0; i < noise000.length; i++) {
            final double n000 = noise000[i];
            final double n100 = this.bts$noise100[i];
            final double n001 = this.bts$noise001[i];
            final double n101 = this.bts$noise101[i];

            this.bts$valueXZ00[i] = n000 + delta * (this.bts$noise010[i] - n000);
            this.bts$valueXZ10[i] = n100 + delta * (this.bts$noise110[i] - n100);
            this.bts$valueXZ01[i] = n001 + delta * (this.bts$noise011[i] - n001);
            this.bts$valueXZ11[i] = n101 + delta * (this.bts$noise111[i] - n101);
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
        for (int j = 0; j < valueXZ00.length; j++) {
            final double v0 = valueXZ00[j];
            final double v1 = this.bts$valueXZ01[j];
            this.bts$valueZ0[j] = v0 + d * (this.bts$valueXZ10[j] - v0);
            this.bts$valueZ1[j] = v1 + d * (this.bts$valueXZ11[j] - v1);
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
        for (int j = 0; j < valueZ0.length; j++) {
            final double v = valueZ0[j];
            this.bts$value[j] = v + d * (this.bts$valueZ1[j] - v);
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

    /**
     * @author Sixik
     * @reason Redirect to flat iterator
     */
    @Overwrite
    private void fillSlice(boolean pIsSlice0, int pStart) {
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
    }
}
