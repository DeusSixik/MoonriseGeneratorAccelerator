package dev.sixik.generator_accelerator.common.aquifer.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferFluidGrid;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferGrid;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferNearest;
import dev.sixik.generator_accelerator.common.aquifer.GAAquiferPlan;
import dev.sixik.generator_accelerator.common.utils.SixikGenerationUtils;
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
        implements GAAquiferPlan.FluidLoader, GAAquiferPlan.BarrierSampler {

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
    private static final BlockState ga$AIR_STATE = Blocks.AIR.defaultBlockState();
    @Unique
    private static final BlockState ga$LAVA_STATE = Blocks.LAVA.defaultBlockState();

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
        this.ga$result = new GAAquiferPlan.Result();
        this.ga$fluidStates = new BlockState[this.aquiferLocationCache.length];
        final RandomSource random = SixikGenerationUtils.getRandom(this.positionalRandomFactory);
        // index: y, z, x
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < this.gridSizeZ; z++) {
                for (int x = 0; x < this.gridSizeX; x++) {
                    final int x1 = x + this.minGridX;
                    final int y1 = y + this.minGridY;
                    final int z1 = z + this.minGridZ;
                    SixikGenerationUtils.derive(this.positionalRandomFactory, random, x1, y1, z1);
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
        final int i = context.blockX();
        final int j = context.blockY();
        final int k = context.blockZ();
        if (substance > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        } else {
            Aquifer.FluidStatus fluidLevel = this.globalFluidPicker.computeFluid(i, j, k);
            if (ga$isAt(fluidLevel, j, Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return ga$LAVA_STATE;
            } else {
                aquiferExtracted$refreshDistPosIdx(i, j, k);
                return aquiferExtracted$applyPost(context, substance, j, i, k);
            }
        }

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

    @Unique
    private Aquifer.FluidStatus ga$getAquiferStatusByIndex(int index) {
        final Aquifer.FluidStatus cached = this.aquiferCache[index];
        if (cached != null) {
            this.ga$populatePrimitiveFluid(index, cached);
            return cached;
        }
        final Aquifer.FluidStatus computed = this.computeFluid(
                this.ga$plan.sampleX(index),
                this.ga$plan.sampleY(index),
                this.ga$plan.sampleZ(index)
        );
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
        return y < this.ga$plan.fluidLevel(index) ? this.ga$fluidStates[index] : ga$AIR_STATE;
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
