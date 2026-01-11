package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import dev.sixik.moonrisegeneratoraccelerator.common.utils.SomeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
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
    private long c2me$pos1;
    @Unique
    private long c2me$pos2;
    @Unique
    private long c2me$pos3;
    @Unique
    private double c2me$mutableDoubleThingy;

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
        final RandomSource random = SomeUtils.getRandom(this.positionalRandomFactory);
        // index: y, z, x
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < this.gridSizeZ; z++) {
                for (int x = 0; x < this.gridSizeX; x++) {
                    final int x1 = x + this.minGridX;
                    final int y1 = y + this.minGridY;
                    final int z1 = z + this.minGridZ;
                    SomeUtils.derive(this.positionalRandomFactory, random, x1, y1, z1);
                    final int x2 = x1 * 16 + random.nextInt(10);
                    final int y2 = y1 * 12 + random.nextInt(9);
                    final int z2 = z1 * 16 + random.nextInt(10);
                    final int index = this.getIndex(x1, y1, z1);
                    this.aquiferLocationCache[index] = BlockPos.asLong(x2, y2, z2);
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
            if (fluidLevel.at(j).is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return Blocks.LAVA.defaultBlockState();
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
        final BlockState blockState = fluidLevel.at(i);
        final BlockState blockState2 = fluidLevel2.at(i);
        if ((!blockState.is(Blocks.LAVA) || !blockState2.is(Blocks.WATER)) && (!blockState.is(Blocks.WATER) || !blockState2.is(Blocks.LAVA))) {
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
        final int m = Math.floorDiv(j, 12); // C2ME - inline
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
        return 1.0D - (double) Math.abs(j - i) * 0.04D;
    }

    @Unique
    private @NotNull BlockState aquiferExtracted$applyPost(DensityFunction.FunctionContext pos, double density, int j, int i, int k) {
        final Aquifer.FluidStatus fluidLevel2 = this.getAquiferStatus(this.c2me$pos1);
        final double d = similarity(this.c2me$dist1, this.c2me$dist2);
        final BlockState blockState = fluidLevel2.at(j);
        if (d <= 0.0) {
            this.shouldScheduleFluidUpdate = d >= FLOWING_UPDATE_SIMULARITY;
            return blockState;
        } else if (blockState.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(i, j - 1, k).at(j - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return blockState;
        } else {
            this.c2me$mutableDoubleThingy = Double.NaN;
            final Aquifer.FluidStatus fluidLevel3 = this.getAquiferStatus(this.c2me$pos2);
            final double e = d * this.c2me$calculateDensityModified(pos, fluidLevel2, fluidLevel3);
            if (density + e > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            } else {
                return aquiferExtracted$getFinalBlockState(pos, density, d, fluidLevel2, fluidLevel3, blockState);
            }
        }
    }

    @Unique
    private BlockState aquiferExtracted$getFinalBlockState(DensityFunction.FunctionContext pos, double density, double d, Aquifer.FluidStatus fluidLevel2, Aquifer.FluidStatus fluidLevel3, BlockState blockState) {
        final Aquifer.FluidStatus fluidLevel4 = this.getAquiferStatus(this.c2me$pos3);
        final double f = similarity(this.c2me$dist1, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, density, d, fluidLevel2, f, fluidLevel4)) return null;

        final double g = similarity(this.c2me$dist2, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, density, d, fluidLevel3, g, fluidLevel4)) return null;

        this.shouldScheduleFluidUpdate = true;
        return blockState;
    }

    @Unique
    private boolean aquiferExtracted$extractedCheckFG(DensityFunction.FunctionContext pos, double density, double d, Aquifer.FluidStatus fluidLevel2, double f, Aquifer.FluidStatus fluidLevel4) {
        if (f > 0.0) {
            final double g = d * f * this.c2me$calculateDensityModified(pos, fluidLevel2, fluidLevel4);
            if (density + g > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return true;
            }
        }
        return false;
    }

    @Unique
    private void aquiferExtracted$refreshDistPosIdx(int x, int y, int z) {
        final int gx = (x - 5) >> 4;
        final int gy = Math.floorDiv(y + 1, 12);
        final int gz = (z - 5) >> 4;

        int localDist1 = Integer.MAX_VALUE;
        int localDist2 = Integer.MAX_VALUE;
        int localDist3 = Integer.MAX_VALUE;
        long localPos1 = 0;
        long localPos2 = 0;
        long localPos3 = 0;

        final int strideY = this.gridSizeZ * this.gridSizeX;
        final int strideZ = this.gridSizeX;

        int baseIndexY = this.getIndex(gx, gy - 1, gz);

        for (int offY = -1; offY <= 1; ++offY) {

            int baseIndexZ = baseIndexY;

            for (int offZ = 0; offZ <= 1; ++offZ) {

                {
                    final int posIdx = baseIndexZ; // +0
                    final long position = this.aquiferLocationCache[posIdx];

                    final int dx = BlockPos.getX(position) - x;
                    final int dy = BlockPos.getY(position) - y;
                    final int dz = BlockPos.getZ(position) - z;
                    final int dist = dx * dx + dy * dy + dz * dz;

                    if (localDist3 >= dist) {
                        if (localDist2 >= dist) {
                            localDist3 = localDist2;
                            localPos3 = localPos2;
                            if (localDist1 >= dist) {
                                localDist2 = localDist1;
                                localPos2 = localPos1;
                                localDist1 = dist;
                                localPos1 = position;
                            } else {
                                localDist2 = dist;
                                localPos2 = position;
                            }
                        } else {
                            localDist3 = dist;
                            localPos3 = position;
                        }
                    }
                }

                {
                    final int posIdx = baseIndexZ + 1;
                    final long position = this.aquiferLocationCache[posIdx];

                    final int dx = BlockPos.getX(position) - x;
                    final int dy = BlockPos.getY(position) - y;
                    final int dz = BlockPos.getZ(position) - z;
                    final int dist = dx * dx + dy * dy + dz * dz;

                    if (localDist3 >= dist) {
                        if (localDist2 >= dist) {
                            localDist3 = localDist2;
                            localPos3 = localPos2;
                            if (localDist1 >= dist) {
                                localDist2 = localDist1;
                                localPos2 = localPos1;
                                localDist1 = dist;
                                localPos1 = position;
                            } else {
                                localDist2 = dist;
                                localPos2 = position;
                            }
                        } else {
                            localDist3 = dist;
                            localPos3 = position;
                        }
                    }
                }

                baseIndexZ += strideZ;
            }
            baseIndexY += strideY;
        }

        this.c2me$dist1 = localDist1;
        this.c2me$dist2 = localDist2;
        this.c2me$dist3 = localDist3;
        this.c2me$pos1 = localPos1;
        this.c2me$pos2 = localPos2;
        this.c2me$pos3 = localPos3;
    }

    @Unique
    private double c2me$calculateDensityModified(
            DensityFunction.FunctionContext pos, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2
    ) {
        final int blockY = pos.blockY();
        final BlockState blockState = fluidLevel.at(blockY);
        final BlockState blockState2 = fluidLevel2.at(blockY);
        if ((!blockState.is(Blocks.LAVA) || !blockState2.is(Blocks.WATER)) && (!blockState.is(Blocks.WATER) || !blockState2.is(Blocks.LAVA))) {
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
