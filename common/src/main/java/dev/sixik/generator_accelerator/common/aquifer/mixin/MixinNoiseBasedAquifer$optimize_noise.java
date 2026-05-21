package dev.sixik.generator_accelerator.common.aquifer.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferColumnBandNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferFluidGrid;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferGrid;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferPlan;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferPrimitiveAccess;
import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlas;
import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlasOwner;
import dev.sixik.generator_accelerator.common.noise.GAUnifiedRegionPacketAccess;
import dev.sixik.generator_accelerator.common.utils.SixikGenerationUtils;
import dev.sixik.generator_accelerator.common.worldgen.region.GAUnifiedRegionPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class MixinNoiseBasedAquifer$optimize_noise
        implements GAAquiferPlan.FluidLoader, GAAquiferPlan.BarrierSampler, GAAquiferPrimitiveAccess {

    @Shadow
    @Final
    private long[] aquiferLocationCache;
    @Shadow
    @Final
    private int gridSizeX;
    @Shadow
    @Final
    private int gridSizeZ;
    @Shadow
    @Final
    private PositionalRandomFactory positionalRandomFactory;
    @Shadow
    @Final
    private int minGridX;
    @Shadow
    @Final
    private int minGridY;
    @Shadow
    @Final
    private int minGridZ;

    @Shadow
    protected abstract int getIndex(int i, int j, int k);

    @Shadow
    private boolean shouldScheduleFluidUpdate;
    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;
    @Shadow
    @Final
    private DensityFunction barrierNoise;

    @Shadow
    @Final
    private static double FLOWING_UPDATE_SIMULARITY;
    @Shadow
    @Final
    private Aquifer.FluidStatus[] aquiferCache;

    @Shadow
    protected abstract Aquifer.FluidStatus computeFluid(int i, int j, int k);

    @Shadow
    @Final
    private DensityFunction erosion;
    @Shadow
    @Final
    private DensityFunction depth;
    @Shadow
    @Final
    private DensityFunction fluidLevelFloodednessNoise;

    @Shadow
    protected abstract int computeRandomizedFluidSurfaceLevel(int i, int j, int k, int l);

    @Unique
    private double c2me$mutableDoubleThingy;
    @Unique
    private GAAquiferNearest ga$nearest;
    @Unique
    private GAAquiferPlan ga$plan;
    @Unique
    private GAAquiferPlan.Result ga$result;
    @Unique
    private BlockState[] ga$fluidStates;
    @Unique
    private DensityFunction.FunctionContext ga$barrierContext;
    @Unique
    private GAAquiferColumnBandNearest ga$columnBandNearest;
    @Unique
    private GAAquiferNearest ga$externalNearestFallback;
    @Unique
    private GARegionalAquiferAtlasOwner ga$regionalAtlasOwner;
    @Unique
    private GARegionalAquiferAtlas.View ga$regionalAtlasView;
    @Unique
    private int[] ga$globalCacheX;
    @Unique
    private int[] ga$globalCacheY;
    @Unique
    private int[] ga$globalCacheZ;
    @Unique
    private int[] ga$globalCacheLevel;
    @Unique
    private int[] ga$globalCacheBlockId;
    @Unique
    private byte[] ga$globalCacheKind;
    @Unique
    private boolean[] ga$globalCacheValid;
    @Unique
    private byte[] ga$globalCacheNextSlot;
    @Unique
    private static final BlockState ga$AIR_STATE = Blocks.AIR.defaultBlockState();
    @Unique
    private static final BlockState ga$LAVA_STATE = Blocks.LAVA.defaultBlockState();
    @Unique
    private static final int GA$GLOBAL_CACHE_COLUMNS = 256;
    @Unique
    private static final int GA$GLOBAL_CACHE_SIZE = GA$GLOBAL_CACHE_COLUMNS * 2;
    @Unique
    private static final int GA$GLOBAL_CACHE_COLUMN_MASK = GA$GLOBAL_CACHE_COLUMNS - 1;
    @Unique
    private static final boolean GA$COLUMN_BAND_NEAREST = !"false".equalsIgnoreCase(System.getProperty(
            "ga.aquifer.columnBandNearest.enabled",
            "true"
    ));

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$init(
            NoiseChunk noiseChunk,
            ChunkPos chunkPos,
            NoiseRouter noiseRouter,
            PositionalRandomFactory positionalRandomFactory,
            int i, int j,
            Aquifer.FluidPicker fluidPicker,
            CallbackInfo ci
    ) {
        if (this.aquiferLocationCache.length % (this.gridSizeX * this.gridSizeZ) != 0) {
            throw new AssertionError("Array length");
        }
        final int sizeY = this.aquiferLocationCache.length / (this.gridSizeX * this.gridSizeZ);
        final int[] aquiferX = new int[this.aquiferLocationCache.length];
        final int[] aquiferY = new int[this.aquiferLocationCache.length];
        final int[] aquiferZ = new int[this.aquiferLocationCache.length];
        this.ga$nearest = new GAAquiferNearest();
        this.ga$externalNearestFallback = new GAAquiferNearest();
        this.ga$columnBandNearest = new GAAquiferColumnBandNearest();
        this.ga$result = new GAAquiferPlan.Result();
        this.ga$fluidStates = new BlockState[this.aquiferLocationCache.length];
        this.ga$globalCacheX = new int[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheY = new int[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheZ = new int[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheLevel = new int[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheBlockId = new int[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheKind = new byte[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheValid = new boolean[GA$GLOBAL_CACHE_SIZE];
        this.ga$globalCacheNextSlot = new byte[GA$GLOBAL_CACHE_COLUMNS];
        this.ga$regionalAtlasOwner = null;
        this.ga$regionalAtlasView = null;
        if (GARegionalAquiferAtlas.enabled()) {
            this.ga$regionalAtlasOwner = new GARegionalAquiferAtlasOwner(
                    this.positionalRandomFactory,
                    this.globalFluidPicker,
                    this.erosion,
                    this.depth,
                    this.fluidLevelFloodednessNoise,
                    this.minGridX,
                    this.minGridY,
                    this.minGridZ,
                    this.gridSizeX,
                    this.gridSizeZ
            );
            if (noiseChunk instanceof GAUnifiedRegionPacketAccess access) {
                GAUnifiedRegionPacket packet = access.ga$unifiedRegionPacket();
                if (packet != null) {
                    packet.attachAquiferOwner(this.ga$regionalAtlasOwner);
                    this.ga$regionalAtlasView = packet.aquiferView();
                }
            }
            if (this.ga$regionalAtlasView == null) {
                this.ga$regionalAtlasView = GARegionalAquiferAtlas.view(
                        this.ga$regionalAtlasOwner,
                        chunkPos.getMinBlockX(),
                        chunkPos.getMinBlockZ()
                );
            }
        }
        final RandomSource reusableRandom = SixikGenerationUtils.tryGetRandom(this.positionalRandomFactory);
        // index: y, z, x
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < this.gridSizeZ; z++) {
                for (int x = 0; x < this.gridSizeX; x++) {
                    final int x1 = x + this.minGridX;
                    final int y1 = y + this.minGridY;
                    final int z1 = z + this.minGridZ;
                    final RandomSource random;
                    if (reusableRandom != null) {
                        SixikGenerationUtils.derive(this.positionalRandomFactory, reusableRandom, x1, y1, z1);
                        random = reusableRandom;
                    } else {
                        random = this.positionalRandomFactory.at(x1, y1, z1);
                    }
                    final int x2 = x1 * 16 + random.nextInt(10);
                    final int y2 = y1 * 12 + random.nextInt(9);
                    final int z2 = z1 * 16 + random.nextInt(10);
                    final int index = this.getIndex(x1, y1, z1);
                    this.aquiferLocationCache[index] = BlockPos.asLong(x2, y2, z2);
                    aquiferX[index] = x2;
                    aquiferY[index] = y2;
                    aquiferZ[index] = z2;
                }
            }
        }
        GAAquiferGrid grid = new GAAquiferGrid(
                this.gridSizeX,
                this.gridSizeZ,
                this.minGridX,
                this.minGridY,
                this.minGridZ,
                aquiferX,
                aquiferY,
                aquiferZ
        );
        this.ga$plan = new GAAquiferPlan(grid, GA$BlockStateExtension.get(ga$AIR_STATE).bts$getFastId(), this);
        for (long blockPosition : this.aquiferLocationCache) {
            if (blockPosition == Long.MAX_VALUE) {
                throw new AssertionError("Array initialization");
            }
        }
    }

    /**
     * @author Sixik
     * @reason none
     */
    @Overwrite
    protected int gridX(int i) {
        return i >> 4;
    }

    /**
     * @author Sixik
     * @reason none
     */
    @Overwrite
    protected int gridZ(int i) {
        return i >> 4;
    }

    /**
     * @author Sixik
     * @reason Inline constant floor division used by aquifer grid indexing.
     */
    @Overwrite
    protected int gridY(int i) {
        return ga$floorDiv12(i);
    }


    /**
     * @author
     * @reason
     */
    @Overwrite
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
        int blockId = this.ga$computeSubstanceId(context, substance, this.ga$columnBandNearest, this.ga$nearest);
        if (blockId == GAAquiferPlan.SOLID_RESULT) {
            return null;
        }
        return FastBlockStateCache.getBlockState(blockId);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble substance, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2) {

        final int i = context.blockY();
        final Block firstBlock = ga$blockAt(fluidLevel, i);
        final Block secondBlock = ga$blockAt(fluidLevel2, i);
        if (!((firstBlock == Blocks.LAVA && secondBlock == Blocks.WATER)
                || (firstBlock == Blocks.WATER && secondBlock == Blocks.LAVA))) {
            final int abs = Math.abs(fluidLevel.fluidLevel - fluidLevel2.fluidLevel);
            if (abs == 0) {
                return 0.0;
            } else {
                final double d = 0.5 * (double) (fluidLevel.fluidLevel + fluidLevel2.fluidLevel);
                final double q = aquiferExtracted$getQ(i, d, abs);
                return aquiferExtracted$postCalculateDensity(context, substance, q);
            }
        }

        return 2.0;

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private Aquifer.FluidStatus getAquiferStatus(long pos) {
        final int i = BlockPos.getX(pos);
        final int j = BlockPos.getY(pos);
        final int k = BlockPos.getZ(pos);
        final int l = i >> 4; // C2ME - inline: floorDiv(i, 16)
        final int m = ga$floorDiv12(j); // C2ME - inline
        final int n = k >> 4; // C2ME - inline: floorDiv(k, 16)
        final int o = this.getIndex(l, m, n);
        final Aquifer.FluidStatus fluidLevel = this.aquiferCache[o];
        if (fluidLevel != null) {
            return fluidLevel;
        } else {
            final Aquifer.FluidStatus fluidLevel2 = this.computeFluid(i, j, k);
            this.aquiferCache[o] = fluidLevel2;
            return fluidLevel2;
        }

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private int computeSurfaceLevel(int x, int y, int z, Aquifer.FluidStatus fluidStatus, int maxSurfaceLevel, boolean fluidPresent) {

        final DensityFunction.SinglePointContext unblendedNoisePos = new DensityFunction.SinglePointContext(x, y, z);
        double d;
        double d1;
        if (OverworldBiomeBuilder.isDeepDarkRegion(this.erosion, this.depth, unblendedNoisePos)) {
            d = -1.0;
            d1 = -1.0;
        } else {
            int i = maxSurfaceLevel + 8 - y;
            double f = fluidPresent ? Mth.clampedLerp(1.0, 0.0, ((double) i) / 64.0) : 0.0; // inline
            double g = Mth.clamp(this.fluidLevelFloodednessNoise.compute(unblendedNoisePos), -1.0, 1.0);
            d = g + 0.8 + (f - 1.0) * 1.2; // inline
            d1 = g + 0.3 + (f - 1.0) * 1.1; // inline
        }

        int i;
        if (d1 > (double) 0.0F) {
            i = fluidStatus.fluidLevel;
        } else if (d > (double) 0.0F) {
            i = this.computeRandomizedFluidSurfaceLevel(x, y, z, maxSurfaceLevel);
        } else {
            i = DimensionType.WAY_BELOW_MIN_Y;
        }

        return i;

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    protected static double similarity(int i, int j) {
        return GAAquiferPlan.similarity(i, j);
    }

    @Unique
    private @NotNull BlockState aquiferExtracted$applyPost(DensityFunction.FunctionContext pos, double density, int j, int i, int k) {
        GAAquiferNearest nearest = this.ga$nearest;
        this.ga$plan.ensureFluid(nearest.idx1);
        double d = GAAquiferPlan.similarity(nearest.dist1, nearest.dist2);
        boolean waterOverLava = d > 0.0D
                && this.ga$plan.kindAt(nearest.idx1, j) == GAAquiferFluidGrid.KIND_WATER
                && ga$isAt(this.globalFluidPicker.computeFluid(i, j - 1, k), j - 1, Blocks.LAVA);
        this.c2me$mutableDoubleThingy = Double.NaN;
        this.ga$barrierContext = pos;
        GAAquiferPlan.Result result = this.ga$result;
        this.ga$plan.resolve(nearest, j, density, waterOverLava, FLOWING_UPDATE_SIMULARITY, this, result);
        this.shouldScheduleFluidUpdate = result.scheduleFluidUpdate;
        if (result.solid) {
            return null;
        }
        return this.ga$stateAtIndex(nearest.idx1, j);
    }

    @Unique
    private void aquiferExtracted$refreshDistPosIdx(int x, int y, int z) {
        this.ga$plan.nearest(x, y, z, this.ga$nearest);
    }

    @Override
    @Unique
    public int ga$computeSubstanceId(
            DensityFunction.FunctionContext context,
            double density,
            GAAquiferColumnBandNearest columnBand,
            GAAquiferNearest nearest
    ) {
        return this.ga$computeSubstanceIdAt(
                context,
                density,
                context.blockX(),
                context.blockY(),
                context.blockZ(),
                columnBand,
                nearest
        );
    }

    @Override
    @Unique
    public int ga$computeSubstanceIdAt(
            DensityFunction.FunctionContext context,
            double density,
            int x,
            int y,
            int z,
            GAAquiferColumnBandNearest columnBand,
            GAAquiferNearest nearest
    ) {
        if (density > 0.0D) {
            this.shouldScheduleFluidUpdate = false;
            return GAAquiferPlan.SOLID_RESULT;
        }

        int globalSlot = this.ga$pickGlobalFluid(x, y, z);
        if (this.ga$kindAtGlobalSlot(globalSlot, y) == GAAquiferFluidGrid.KIND_LAVA) {
            this.shouldScheduleFluidUpdate = false;
            return this.ga$globalCacheBlockId[globalSlot];
        }

        GAAquiferNearest nearestScratch = nearest == null ? this.ga$externalNearestFallback : nearest;
        if (GA$COLUMN_BAND_NEAREST) {
            this.ga$plan.nearestColumnBand(x, y, z, columnBand, nearestScratch);
        } else {
            this.ga$plan.nearest(x, y, z, nearestScratch);
        }
        this.ga$plan.ensureFluid(nearestScratch.idx1);
        double similarity = GAAquiferPlan.similarity(nearestScratch.dist1, nearestScratch.dist2);
        boolean waterOverLava = similarity > 0.0D
                && this.ga$plan.kindAt(nearestScratch.idx1, y) == GAAquiferFluidGrid.KIND_WATER
                && this.ga$globalFluidKindAt(x, y - 1, z) == GAAquiferFluidGrid.KIND_LAVA;

        this.c2me$mutableDoubleThingy = Double.NaN;
        this.ga$barrierContext = context;
        long packed = this.ga$plan.resolvePacked(
                nearestScratch,
                y,
                density,
                waterOverLava,
                similarity,
                FLOWING_UPDATE_SIMULARITY,
                this
        );
        this.shouldScheduleFluidUpdate = GAAquiferPlan.packedScheduleFluidUpdate(packed);
        return GAAquiferPlan.packedBlockId(packed);
    }

    @Override
    @Unique
    public boolean ga$lastShouldScheduleFluidUpdate() {
        return this.shouldScheduleFluidUpdate;
    }

    @Override
    @Unique
    public byte ga$globalFluidKindAt(int x, int y, int z) {
        int slot = this.ga$pickGlobalFluid(x, y, z);
        return this.ga$kindAtGlobalSlot(slot, y);
    }

    @Override
    @Unique
    public int ga$globalFluidLevelAt(int x, int y, int z) {
        return this.ga$globalCacheLevel[this.ga$pickGlobalFluid(x, y, z)];
    }

    @Override
    @Unique
    public int ga$globalFluidBlockIdAt(int x, int y, int z) {
        return this.ga$globalCacheBlockId[this.ga$pickGlobalFluid(x, y, z)];
    }

    @Unique
    private Aquifer.FluidStatus ga$getAquiferStatusByIndex(int index) {
        final Aquifer.FluidStatus cached = this.aquiferCache[index];
        if (cached != null) {
            this.ga$populatePrimitiveFluid(index, cached);
            return cached;
        }

        int sampleX = this.ga$plan.sampleX(index);
        int sampleY = this.ga$plan.sampleY(index);
        int sampleZ = this.ga$plan.sampleZ(index);
        GARegionalAquiferAtlas.View atlasView = this.ga$regionalAtlasView;
        if (atlasView != null && atlasView.enabled()) {
            GARegionalAquiferAtlas.Sample regionalSample = atlasView.samplePoint(
                    sampleX,
                    sampleY,
                    sampleZ,
                    () -> {
                        Aquifer.FluidStatus computed = this.computeFluid(sampleX, sampleY, sampleZ);
                        BlockState fluidState = ((MixinFluidStatusAccessor) (Object) computed).ga$getFluidType();
                        return new GARegionalAquiferAtlas.Sample(
                                computed.fluidLevel,
                                ga$fluidKind(fluidState),
                                GA$BlockStateExtension.get(fluidState).bts$getFastId()
                        );
                    }
            );
            if (regionalSample != null && regionalSample != GARegionalAquiferAtlas.Sample.FALLBACK) {
                this.ga$plan.setFluid(index, regionalSample.fluidLevel(), regionalSample.fluidKind(), regionalSample.blockId());
                return null;
            }
        }

        final Aquifer.FluidStatus computed = this.computeFluid(sampleX, sampleY, sampleZ);
        this.aquiferCache[index] = computed;
        this.ga$populatePrimitiveFluid(index, computed);
        return computed;
    }

    @Override
    @Unique
    public void ga$loadAquiferFluid(int index) {
        this.ga$getAquiferStatusByIndex(index);
    }

    @Override
    @Unique
    public double ga$sampleBarrierNoise() {
        return this.ga$getCachedBarrierNoise(this.ga$barrierContext);
    }

    @Unique
    private void ga$populatePrimitiveFluid(int index, Aquifer.FluidStatus status) {
        if (this.ga$plan.hasFluid(index)) {
            return;
        }
        BlockState fluidState = ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType();
        this.ga$fluidStates[index] = fluidState;
        this.ga$plan.setFluid(
                index,
                status.fluidLevel,
                ga$fluidKind(fluidState),
                GA$BlockStateExtension.get(fluidState).bts$getFastId()
        );
    }

    @Unique
    private BlockState ga$stateAtIndex(int index, int y) {
        if (y >= this.ga$plan.fluidLevel(index)) {
            return ga$AIR_STATE;
        }
        BlockState state = this.ga$fluidStates[index];
        return state != null ? state : FastBlockStateCache.getBlockState(this.ga$plan.blockIdAt(index, y));
    }

    @Unique
    private int ga$pickGlobalFluid(int x, int y, int z) {
        int column = ga$globalCacheColumn(x, z);
        int slot = column << 1;
        if (this.ga$globalCacheMatches(slot, x, y, z)) {
            return slot;
        }
        int alternateSlot = slot | 1;
        if (this.ga$globalCacheMatches(alternateSlot, x, y, z)) {
            return alternateSlot;
        }
        int selected = this.ga$globalCacheNextSlot[column] ^ 1;
        this.ga$globalCacheNextSlot[column] = (byte) selected;
        slot |= selected;
        GARegionalAquiferAtlas.Sample sample = this.ga$loadGlobalFluidSample(x, y, z);
        this.ga$globalCacheX[slot] = x;
        this.ga$globalCacheY[slot] = y;
        this.ga$globalCacheZ[slot] = z;
        this.ga$globalCacheLevel[slot] = sample.fluidLevel();
        this.ga$globalCacheKind[slot] = sample.fluidKind();
        this.ga$globalCacheBlockId[slot] = sample.blockId();
        this.ga$globalCacheValid[slot] = true;
        return slot;
    }

    @Unique
    private GARegionalAquiferAtlas.Sample ga$loadGlobalFluidSample(int x, int y, int z) {
        GARegionalAquiferAtlas.View atlasView = this.ga$regionalAtlasView;
        if (atlasView != null && atlasView.enabled()) {
            return atlasView.globalFluid(x, y, z, () -> this.ga$computeGlobalFluidSample(x, y, z));
        }
        return this.ga$computeGlobalFluidSample(x, y, z);
    }

    @Unique
    private GARegionalAquiferAtlas.Sample ga$computeGlobalFluidSample(int x, int y, int z) {
        Aquifer.FluidStatus status = this.globalFluidPicker.computeFluid(x, y, z);
        BlockState fluidState = ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType();
        return new GARegionalAquiferAtlas.Sample(
                status.fluidLevel,
                ga$fluidKind(fluidState),
                GA$BlockStateExtension.get(fluidState).bts$getFastId()
        );
    }

    @Unique
    private boolean ga$globalCacheMatches(int slot, int x, int y, int z) {
        return this.ga$globalCacheValid[slot]
                && this.ga$globalCacheX[slot] == x
                && this.ga$globalCacheY[slot] == y
                && this.ga$globalCacheZ[slot] == z;
    }

    @Unique
    private byte ga$kindAtGlobalSlot(int slot, int y) {
        return y < this.ga$globalCacheLevel[slot] ? this.ga$globalCacheKind[slot] : GAAquiferFluidGrid.KIND_AIR;
    }

    @Unique
    private static int ga$globalCacheColumn(int x, int z) {
        return (((z & 15) << 4) | (x & 15)) & GA$GLOBAL_CACHE_COLUMN_MASK;
    }

    @Unique
    private static byte ga$fluidKind(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.WATER) {
            return GAAquiferFluidGrid.KIND_WATER;
        }
        if (block == Blocks.LAVA) {
            return GAAquiferFluidGrid.KIND_LAVA;
        }
        if (block == Blocks.AIR) {
            return GAAquiferFluidGrid.KIND_AIR;
        }
        return GAAquiferFluidGrid.KIND_OTHER;
    }

    @Unique
    private static int ga$floorDiv12(int value) {
        return GAAquiferGrid.floorDiv12(value);
    }

    @Unique
    private static boolean ga$isAt(Aquifer.FluidStatus status, int y, Block block) {
        return y < status.fluidLevel
                && ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType().getBlock() == block;
    }

    @Unique
    private static Block ga$blockAt(Aquifer.FluidStatus status, int y) {
        return y < status.fluidLevel
                ? ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType().getBlock()
                : Blocks.AIR;
    }

    @Unique
    private double ga$getCachedBarrierNoise(DensityFunction.FunctionContext pos) {
        final double cached = this.c2me$mutableDoubleThingy;
        if (!Double.isNaN(cached)) {
            return cached;
        }
        final double computed = this.barrierNoise.compute(pos);
        this.c2me$mutableDoubleThingy = computed;
        return computed;
    }

    @Unique
    private double aquiferExtracted$postCalculateDensity(DensityFunction.FunctionContext pos, MutableDouble mutableDouble, double q) {
        double r;
        if (!(q < -2.0) && !(q > 2.0)) {
            final double s = mutableDouble.getValue();
            if (Double.isNaN(s)) {
                final double t = this.barrierNoise.compute(pos);
                mutableDouble.setValue(t);
                r = t;
            } else {
                r = s;
            }
        } else {
            r = 0.0;
        }

        return 2.0 * (r + q);
    }

    @Unique
    private static double aquiferExtracted$getQ(double i, double d, double j) {
        final double e = i + 0.5 - d;
        final double f = j * 0.5;
        final double o = f - Math.abs(e);

        if (e > 0.0) {
            if (o > 0.0) {
                return o * 0.6666666666666666; // o / 1.5
            } else {
                return o * 0.4; // o / 2.5
            }
        } else {
            final double p = 3.0 + o;
            if (p > 0.0) {
                return p * 0.3333333333333333; // p / 3.0
            } else {
                return p * 0.1; // p / 10.0
            }
        }
    }
}
