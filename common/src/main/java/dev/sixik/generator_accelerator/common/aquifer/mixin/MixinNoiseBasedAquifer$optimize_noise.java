package dev.sixik.generator_accelerator.common.aquifer.mixin;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.aquifer.AquiferStats;
import dev.sixik.generator_accelerator.utils.GAGenerationUtils;
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
public abstract class MixinNoiseBasedAquifer$optimize_noise {
    @Unique
    private static boolean ga$statsEnabled() {
        return GeneratorAccelerator.isUseProfiler();
    }

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
    private int c2me$dist1;
    @Unique
    private int c2me$dist2;
    @Unique
    private int c2me$dist3;
    @Unique
    private int c2me$idx1;
    @Unique
    private int c2me$idx2;
    @Unique
    private int c2me$idx3;
    @Unique
    private int c2me$queryX;
    @Unique
    private int c2me$queryY;
    @Unique
    private int c2me$queryZ;
    @Unique
    private double c2me$mutableDoubleThingy;

    @Unique
    private static final BlockState ga$AIR_STATE = Blocks.AIR.defaultBlockState();
    @Unique
    private static final BlockState ga$LAVA_STATE = Blocks.LAVA.defaultBlockState();
    @Unique
    private static final double ga$INV_64 = 1.0 / 64.0;

    @Unique
    private int[] ga$aquiferPosX;
    @Unique
    private int[] ga$aquiferPosY;
    @Unique
    private int[] ga$aquiferPosZ;

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
        this.ga$aquiferPosX = new int[this.aquiferLocationCache.length];
        this.ga$aquiferPosY = new int[this.aquiferLocationCache.length];
        this.ga$aquiferPosZ = new int[this.aquiferLocationCache.length];
        final int sizeY = this.aquiferLocationCache.length / (this.gridSizeX * this.gridSizeZ);
        final RandomSource random = GAGenerationUtils.getRandom(this.positionalRandomFactory);
        // index: y, z, x
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < this.gridSizeZ; z++) {
                for (int x = 0; x < this.gridSizeX; x++) {
                    final int x1 = x + this.minGridX;
                    final int y1 = y + this.minGridY;
                    final int z1 = z + this.minGridZ;
                    GAGenerationUtils.derive(this.positionalRandomFactory, random, x1, y1, z1);
                    final int x2 = x1 * 16 + random.nextInt(10);
                    final int y2 = y1 * 12 + random.nextInt(9);
                    final int z2 = z1 * 16 + random.nextInt(10);
                    final int index = this.getIndex(x1, y1, z1);
                    this.aquiferLocationCache[index] = BlockPos.asLong(x2, y2, z2);
                    this.ga$aquiferPosX[index] = x2;
                    this.ga$aquiferPosY[index] = y2;
                    this.ga$aquiferPosZ[index] = z2;
                }
            }
        }
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
        if (ga$statsEnabled()) {
            AquiferStats.recordComputeSubstanceCall();
        }
        final int i = context.blockX();
        final int j = context.blockY();
        final int k = context.blockZ();
        if (substance > 0.0) {
            if (ga$statsEnabled()) {
                AquiferStats.recordPositiveDensityReturn();
            }
            this.shouldScheduleFluidUpdate = false;
            return null;
        }

        final Aquifer.FluidStatus fluidLevel = this.globalFluidPicker.computeFluid(i, j, k);
        final BlockState fluidType = ga$fluidType(fluidLevel);
        if (j < fluidLevel.fluidLevel && fluidType.getBlock() == Blocks.LAVA) {
            if (ga$statsEnabled()) {
                AquiferStats.recordGlobalLavaReturn();
            }
            this.shouldScheduleFluidUpdate = false;
            return ga$LAVA_STATE;
        }

        aquiferExtracted$refreshDistPosIdx(i, j, k);
        return aquiferExtracted$applyPost(context, substance, j, i, k);

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble substance, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2) {

        final int i = context.blockY();
        final Block firstBlock = ga$blockAt(fluidLevel, ga$fluidType(fluidLevel), i);
        final Block secondBlock = ga$blockAt(fluidLevel2, ga$fluidType(fluidLevel2), i);
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
            double f = fluidPresent ? Mth.clampedLerp(1.0, 0.0, ((double) i) * ga$INV_64) : 0.0; // inline
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
        return 1.0D - (double) Math.abs(j - i) * 0.04D;
    }

    @Unique
    private @NotNull BlockState aquiferExtracted$applyPost(DensityFunction.FunctionContext pos, double density, int j, int i, int k) {
        final Aquifer.FluidStatus fluidLevel2 = this.ga$getAquiferStatusByIndex(this.c2me$idx1);
        final double d = similarity(this.c2me$dist1, this.c2me$dist2);
        final BlockState fluidType2 = ga$fluidType(fluidLevel2);
        final BlockState blockState = ga$at(fluidLevel2, fluidType2, j);
        if (d <= 0.0) {
            this.shouldScheduleFluidUpdate = d >= FLOWING_UPDATE_SIMULARITY;
            return blockState;
        } else if (blockState.getBlock() == Blocks.WATER
                && ga$isAt(this.globalFluidPicker.computeFluid(i, j - 1, k), j - 1, Blocks.LAVA)) {
            if (ga$statsEnabled()) {
                AquiferStats.recordWaterBelowLavaReturn();
            }
            this.shouldScheduleFluidUpdate = true;
            return blockState;
        }

        this.c2me$mutableDoubleThingy = Double.NaN;
        final Aquifer.FluidStatus fluidLevel3 = this.ga$getAquiferStatusByIndex(this.c2me$idx2);
        final double e = d * this.c2me$calculateDensityModified(pos, j, fluidLevel2, fluidLevel3);
        if (density + e > 0.0) {
            if (ga$statsEnabled()) {
                AquiferStats.recordPressureAbortReturn();
            }
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        return aquiferExtracted$getFinalBlockState(pos, j, density, d, fluidLevel2, fluidLevel3, blockState);
    }

    @Unique
    private BlockState aquiferExtracted$getFinalBlockState(DensityFunction.FunctionContext pos, int blockY, double density, double d, Aquifer.FluidStatus fluidLevel2, Aquifer.FluidStatus fluidLevel3, BlockState blockState) {
        this.aquiferExtracted$ensureThirdNearest();
        final Aquifer.FluidStatus fluidLevel4 = this.ga$getAquiferStatusByIndex(this.c2me$idx3);
        final double f = similarity(this.c2me$dist1, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, blockY, density, d, fluidLevel2, f, fluidLevel4)) return null;

        final double g = similarity(this.c2me$dist2, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, blockY, density, d, fluidLevel3, g, fluidLevel4)) return null;

        if (ga$statsEnabled()) {
            AquiferStats.recordFinalSolidReturn();
        }
        this.shouldScheduleFluidUpdate = true;
        return blockState;
    }

    @Unique
    private boolean aquiferExtracted$extractedCheckFG(DensityFunction.FunctionContext pos, int blockY, double density, double d, Aquifer.FluidStatus fluidLevel2, double f, Aquifer.FluidStatus fluidLevel4) {
        if (f > 0.0) {
            final double g = d * f * this.c2me$calculateDensityModified(pos, blockY, fluidLevel2, fluidLevel4);
            if (density + g > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return true;
            }
        }
        return false;
    }

    @Unique
    private void aquiferExtracted$refreshDistPosIdx(int x, int y, int z) {
        final long startNanos = ga$statsEnabled() ? AquiferStats.sampleRefreshDistStart() : 0L;
        if (ga$statsEnabled()) {
            AquiferStats.recordRefreshDistCall();
        }
        this.c2me$queryX = x;
        this.c2me$queryY = y;
        this.c2me$queryZ = z;
        final int gx = (x - 5) >> 4;
        final int gy = ga$floorDiv12(y + 1);
        final int gz = (z - 5) >> 4;

        int localDist1 = Integer.MAX_VALUE;
        int localDist2 = Integer.MAX_VALUE;
        int localIdx1 = 0;
        int localIdx2 = 0;

        final int strideY = this.gridSizeZ * this.gridSizeX;
        final int strideZ = this.gridSizeX;

        int baseIndexY = (((gy - 1 - this.minGridY) * this.gridSizeZ + (gz - this.minGridZ)) * this.gridSizeX)
                + (gx - this.minGridX);

        for (int offY = -1; offY <= 1; ++offY) {

            int baseIndexZ = baseIndexY;

            for (int offZ = 0; offZ <= 1; ++offZ) {

                {
                    final int posIdx = baseIndexZ; // +0
                    final int dx = this.ga$aquiferPosX[posIdx] - x;
                    final int dy = this.ga$aquiferPosY[posIdx] - y;
                    final int dz = this.ga$aquiferPosZ[posIdx] - z;
                    final int dist = dx * dx + dy * dy + dz * dz;

                    if (localDist2 >= dist) {
                        if (localDist1 >= dist) {
                            localDist2 = localDist1;
                            localIdx2 = localIdx1;
                            localDist1 = dist;
                            localIdx1 = posIdx;
                        } else {
                            localDist2 = dist;
                            localIdx2 = posIdx;
                        }
                    }
                }

                {
                    final int posIdx = baseIndexZ + 1;
                    final int dx = this.ga$aquiferPosX[posIdx] - x;
                    final int dy = this.ga$aquiferPosY[posIdx] - y;
                    final int dz = this.ga$aquiferPosZ[posIdx] - z;
                    final int dist = dx * dx + dy * dy + dz * dz;

                    if (localDist2 >= dist) {
                        if (localDist1 >= dist) {
                            localDist2 = localDist1;
                            localIdx2 = localIdx1;
                            localDist1 = dist;
                            localIdx1 = posIdx;
                        } else {
                            localDist2 = dist;
                            localIdx2 = posIdx;
                        }
                    }
                }

                baseIndexZ += strideZ;
            }
            baseIndexY += strideY;
        }

        this.c2me$dist1 = localDist1;
        this.c2me$dist2 = localDist2;
        this.c2me$dist3 = Integer.MAX_VALUE;
        this.c2me$idx1 = localIdx1;
        this.c2me$idx2 = localIdx2;
        this.c2me$idx3 = -1;
        if (startNanos != 0L) {
            AquiferStats.recordRefreshDistTimed(System.nanoTime() - startNanos);
        }
    }

    @Unique
    private void aquiferExtracted$ensureThirdNearest() {
        if (this.c2me$idx3 >= 0) {
            return;
        }
        final long startNanos = ga$statsEnabled() ? AquiferStats.sampleLazyThirdStart() : 0L;
        if (ga$statsEnabled()) {
            AquiferStats.recordLazyThirdResolve();
        }

        final int x = this.c2me$queryX;
        final int y = this.c2me$queryY;
        final int z = this.c2me$queryZ;
        final int gx = (x - 5) >> 4;
        final int gy = ga$floorDiv12(y + 1);
        final int gz = (z - 5) >> 4;

        int localDist3 = Integer.MAX_VALUE;
        int localIdx3 = 0;

        final int strideY = this.gridSizeZ * this.gridSizeX;
        final int strideZ = this.gridSizeX;
        final int idx1 = this.c2me$idx1;
        final int idx2 = this.c2me$idx2;

        int baseIndexY = (((gy - 1 - this.minGridY) * this.gridSizeZ + (gz - this.minGridZ)) * this.gridSizeX)
                + (gx - this.minGridX);

        for (int offY = -1; offY <= 1; ++offY) {
            int baseIndexZ = baseIndexY;

            for (int offZ = 0; offZ <= 1; ++offZ) {
                {
                    final int posIdx = baseIndexZ;
                    if (posIdx != idx1 && posIdx != idx2) {
                        final int dx = this.ga$aquiferPosX[posIdx] - x;
                        final int dy = this.ga$aquiferPosY[posIdx] - y;
                        final int dz = this.ga$aquiferPosZ[posIdx] - z;
                        final int dist = dx * dx + dy * dy + dz * dz;
                        if (localDist3 >= dist) {
                            localDist3 = dist;
                            localIdx3 = posIdx;
                        }
                    }
                }

                {
                    final int posIdx = baseIndexZ + 1;
                    if (posIdx != idx1 && posIdx != idx2) {
                        final int dx = this.ga$aquiferPosX[posIdx] - x;
                        final int dy = this.ga$aquiferPosY[posIdx] - y;
                        final int dz = this.ga$aquiferPosZ[posIdx] - z;
                        final int dist = dx * dx + dy * dy + dz * dz;
                        if (localDist3 >= dist) {
                            localDist3 = dist;
                            localIdx3 = posIdx;
                        }
                    }
                }

                baseIndexZ += strideZ;
            }
            baseIndexY += strideY;
        }

        this.c2me$dist3 = localDist3;
        this.c2me$idx3 = localIdx3;
        if (startNanos != 0L) {
            AquiferStats.recordLazyThirdTimed(System.nanoTime() - startNanos);
        }
    }

    @Unique
    private Aquifer.FluidStatus ga$getAquiferStatusByIndex(int index) {
        final long startNanos = ga$statsEnabled() ? AquiferStats.sampleAquiferStatusStart() : 0L;
        final Aquifer.FluidStatus cached = this.aquiferCache[index];
        if (cached != null) {
            if (startNanos != 0L) {
                AquiferStats.recordAquiferStatusTimed(System.nanoTime() - startNanos);
            }
            return cached;
        }

        final Aquifer.FluidStatus computed = this.computeFluid(
                this.ga$aquiferPosX[index],
                this.ga$aquiferPosY[index],
                this.ga$aquiferPosZ[index]
        );
        this.aquiferCache[index] = computed;
        if (startNanos != 0L) {
            AquiferStats.recordAquiferStatusTimed(System.nanoTime() - startNanos);
        }
        return computed;
    }

    @Unique
    private static int ga$floorDiv12(int value) {
        return value >= 0 ? value / 12 : -((-value + 11) / 12);
    }

    @Unique
    private static BlockState ga$at(Aquifer.FluidStatus status, int y) {
        return ga$at(status, ga$fluidType(status), y);
    }

    @Unique
    private static BlockState ga$at(Aquifer.FluidStatus status, BlockState fluidType, int y) {
        return y < status.fluidLevel
                ? fluidType
                : ga$AIR_STATE;
    }

    @Unique
    private static boolean ga$isAt(Aquifer.FluidStatus status, int y, Block block) {
        return y < status.fluidLevel
                && ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType().getBlock() == block;
    }

    @Unique
    private static Block ga$blockAt(Aquifer.FluidStatus status, int y) {
        return ga$blockAt(status, ga$fluidType(status), y);
    }

    @Unique
    private static Block ga$blockAt(Aquifer.FluidStatus status, BlockState fluidType, int y) {
        return y < status.fluidLevel
                ? fluidType.getBlock()
                : Blocks.AIR;
    }

    @Unique
    private static BlockState ga$fluidType(Aquifer.FluidStatus status) {
        return ((MixinFluidStatusAccessor) (Object) status).ga$getFluidType();
    }

    @Unique
    private double c2me$calculateDensityModified(
            DensityFunction.FunctionContext pos, int blockY, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2
    ) {
        final Block firstBlock = ga$blockAt(fluidLevel, ga$fluidType(fluidLevel), blockY);
        final Block secondBlock = ga$blockAt(fluidLevel2, ga$fluidType(fluidLevel2), blockY);
        if (!((firstBlock == Blocks.LAVA && secondBlock == Blocks.WATER)
                || (firstBlock == Blocks.WATER && secondBlock == Blocks.LAVA))) {
            final int abs = Math.abs(fluidLevel.fluidLevel - fluidLevel2.fluidLevel);
            if (abs == 0) {
                return 0.0;
            } else {
                final double d = 0.5 * (double) (fluidLevel.fluidLevel + fluidLevel2.fluidLevel);
                final double q = aquiferExtracted$getQ(blockY, d, abs);

                return aquiferExtracted$postCalculateDensityModified(pos, q);
            }
        } else {
            return 2.0;
        }
    }

    @Unique
    private double aquiferExtracted$postCalculateDensity(DensityFunction.FunctionContext pos, MutableDouble mutableDouble, double q) {
        double r;
        if (!(q < -2.0) && !(q > 2.0)) {
            final double s = mutableDouble.getValue();
            if (Double.isNaN(s)) {
                if (ga$statsEnabled()) {
                    AquiferStats.recordBarrierNoiseCompute();
                }
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
    private double aquiferExtracted$postCalculateDensityModified(DensityFunction.FunctionContext pos, double q) {
        double r;
        if (!(q < -2.0) && !(q > 2.0)) {
            final double s = this.c2me$mutableDoubleThingy;
            if (Double.isNaN(s)) {
                if (ga$statsEnabled()) {
                    AquiferStats.recordBarrierNoiseCompute();
                }
                final double t = this.barrierNoise.compute(pos);
                this.c2me$mutableDoubleThingy = t;
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
