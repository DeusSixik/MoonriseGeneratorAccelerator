package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import dev.sixik.generator_accelerator.common.carver.CarveStateScratch;
import dev.sixik.generator_accelerator.common.carver.CarverChunkWriter;
import dev.sixik.generator_accelerator.common.carver.CarverReplaceableCache;
import dev.sixik.generator_accelerator.common.carver.CanyonSkipChecker;
import dev.sixik.generator_accelerator.common.carver.CaveSkipChecker;
import dev.sixik.generator_accelerator.common.carver.MutableFunctionContext;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinNoiseBasedAquiferAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.material.FluidState;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.function.Function;

@Mixin(WorldCarver.class)
public abstract class MixinWorldCarver<C extends CarverConfiguration> {

    @Unique
    private static final int GA$NO_DIRECT_STATE = Integer.MIN_VALUE;

    @Unique
    private static final boolean GA$FAST_SIMPLE_CAVE_STATE = !"false".equalsIgnoreCase(System.getProperty("ga.carver.fastSimpleCaveState", "true"));

    @Unique
    private static final boolean GA$FAST_SKIP_TOP_MATERIAL = !"false".equalsIgnoreCase(System.getProperty("ga.carver.fastSkipTopMaterial", "true"));

    @Unique
    private static final BlockState GA$CAVE_AIR_BLOCK = Blocks.CAVE_AIR.defaultBlockState();

    @Unique
    private static final BlockState GA$LAVA_BLOCK = Blocks.LAVA.defaultBlockState();

    @Unique
    private static final int GA$CAVE_AIR_STATE_ID = GA$BlockStateExtension.get(GA$CAVE_AIR_BLOCK).bts$getFastId();

    @Unique
    private static final int GA$LAVA_STATE_ID = GA$BlockStateExtension.get(GA$LAVA_BLOCK).bts$getFastId();

    @Unique
    private static final int GA$GRASS_STATE_ID = GA$BlockStateExtension.get(Blocks.GRASS_BLOCK.defaultBlockState()).bts$getFastId();

    @Unique
    private static final int GA$MYCELIUM_STATE_ID = GA$BlockStateExtension.get(Blocks.MYCELIUM.defaultBlockState()).bts$getFastId();

    @Unique
    private static final int GA$DIRT_STATE_ID = GA$BlockStateExtension.get(Blocks.DIRT.defaultBlockState()).bts$getFastId();

    @Shadow
    protected abstract boolean canReplaceBlock(C carverConfiguration, BlockState blockState);

    @Shadow
    private static boolean isDebugEnabled(CarverConfiguration carverConfiguration) {
        throw new AssertionError();
    }

    @Shadow
    private static BlockState getDebugState(CarverConfiguration carverConfiguration, BlockState blockState) {
        throw new AssertionError();
    }

    @Unique
    private ThreadLocal<MutableFunctionContext> ga$mutableFunctionContext;

    @Unique
    private ThreadLocal<CarveStateScratch> ga$carveStateScratch;

    @Unique
    private ThreadLocal<CarverChunkWriter> ga$carverChunkWriter;

    /**
     * @author Sixik
     * @reason Fold the mask get/set pair into a single fast path and reuse mutable helpers in the inner carve loop.
     */
    @Overwrite
    protected boolean carveEllipsoid(
            CarvingContext carvingContext,
            C carverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            Aquifer aquifer,
            double d,
            double e,
            double f,
            double g,
            double h,
            CarvingMask carvingMask,
            WorldCarver.CarveSkipChecker carveSkipChecker
    ) {
        ChunkPos chunkPos = chunkAccess.getPos();
        double horizontalRadius = 16.0D + g * 2.0D;
        if (Math.abs(d - chunkPos.getMiddleBlockX()) > horizontalRadius || Math.abs(f - chunkPos.getMiddleBlockZ()) > horizontalRadius) {
            return false;
        }

        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        int minX = Math.max(net.minecraft.util.Mth.floor(d - g) - minBlockX - 1, 0);
        int maxX = Math.min(net.minecraft.util.Mth.floor(d + g) - minBlockX, 15);
        int minY = Math.max(net.minecraft.util.Mth.floor(e - h) - 1, carvingContext.getMinGenY() + 1);
        int surfaceTrim = chunkAccess.isUpgrading() ? 0 : 7;
        int maxY = Math.min(net.minecraft.util.Mth.floor(e + h) + 1, carvingContext.getMinGenY() + carvingContext.getGenDepth() - 1 - surfaceTrim);
        int minZ = Math.max(net.minecraft.util.Mth.floor(f - g) - minBlockZ - 1, 0);
        int maxZ = Math.min(net.minecraft.util.Mth.floor(f + g) - minBlockZ, 15);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }

        boolean debug = isDebugEnabled(carverConfiguration);
        boolean carvedAny = false;
        GA$CarvingMaskExtension maskExtension = (GA$CarvingMaskExtension) carvingMask;
        CarveStateScratch carveStateScratch = this.ga$getCarveStateScratch();
        BlockPos.MutableBlockPos carvePos = carveStateScratch.carvePos();
        BlockPos.MutableBlockPos belowPos = carveStateScratch.belowPos();
        MutableBoolean surfaceHit = carveStateScratch.surfaceHit();
        CarverChunkWriter chunkWriter = this.ga$getCarverChunkWriter();
        boolean restoreSurface = !GA$FAST_SIMPLE_CAVE_STATE || debug || !GA$FAST_SKIP_TOP_MATERIAL;
        carveStateScratch.set(
                carverConfiguration.lavaLevel.resolveY(carvingContext),
                debug,
                restoreSurface,
                debug ? null : CarverReplaceableCache.get(carverConfiguration)
        );
        chunkWriter.begin(chunkAccess);

        CaveSkipChecker caveSkipChecker = carveSkipChecker instanceof CaveSkipChecker checker ? checker : null;
        CanyonSkipChecker canyonSkipChecker = carveSkipChecker instanceof CanyonSkipChecker checker ? checker : null;
        double inverseHorizontalRadius = 1.0D / g;
        double inverseVerticalRadius = 1.0D / h;
        double scaledZStart = ((double) (minBlockZ + minZ) + 0.5D - f) * inverseHorizontalRadius;

        try {
            for (int localX = minX; localX <= maxX; ++localX) {
                int blockX = minBlockX + localX;
                double scaledX = ((double) blockX + 0.5D - d) * inverseHorizontalRadius;
                double scaledX2 = scaledX * scaledX;
                double scaledZ = scaledZStart;

                for (int localZ = minZ; localZ <= maxZ; ++localZ) {
                    int blockZ = minBlockZ + localZ;
                    double scaledXZ = scaledX2 + scaledZ * scaledZ;
                    if (scaledXZ >= 1.0D) {
                        scaledZ += inverseHorizontalRadius;
                        continue;
                    }

                    surfaceHit.setFalse();
                    double scaledY = ((double) maxY - 0.5D - e) * inverseVerticalRadius;
                    int canyonWidthIndex = canyonSkipChecker != null ? maxY - canyonSkipChecker.getMinGenY() - 1 : -1;
                    for (int y = maxY; y > minY; --y) {
                        double scaledY2 = scaledY * scaledY;
                        if (caveSkipChecker != null) {
                            if (scaledY <= caveSkipChecker.getFloorLevel() || scaledXZ + scaledY2 >= 1.0D) {
                                scaledY -= inverseVerticalRadius;
                                continue;
                            }
                        } else if (canyonSkipChecker != null) {
                            if (scaledXZ * canyonSkipChecker.getWidthFactors()[canyonWidthIndex] + scaledY2 * (1.0D / 6.0D) >= 1.0D) {
                                scaledY -= inverseVerticalRadius;
                                canyonWidthIndex--;
                                continue;
                            }
                        } else if (carveSkipChecker.shouldSkip(carvingContext, scaledX, scaledY, scaledZ, y)) {
                            scaledY -= inverseVerticalRadius;
                            continue;
                        }

                        if (!maskExtension.ga$setIfAbsent(localX, y, localZ)) {
                            if (!debug) {
                                scaledY -= inverseVerticalRadius;
                                if (canyonWidthIndex >= 0) {
                                    canyonWidthIndex--;
                                }
                                continue;
                            }
                            carvingMask.set(localX, y, localZ);
                        }

                        carvePos.set(blockX, y, blockZ);
                        carvedAny |= this.carveBlock(
                                carvingContext,
                                carverConfiguration,
                                chunkAccess,
                                function,
                                carvingMask,
                                carvePos,
                                belowPos,
                                aquifer,
                                surfaceHit
                        );

                        scaledY -= inverseVerticalRadius;
                        if (canyonWidthIndex >= 0) {
                            canyonWidthIndex--;
                        }
                    }

                    scaledZ += inverseHorizontalRadius;
                }
            }
        } finally {
            chunkWriter.end();
            carveStateScratch.clear();
        }

        return carvedAny;
    }

    /**
     * @author Sixik
     * @reason Reuse a mutable density context and avoid per-call lambda allocation when restoring top material.
     */
    @Overwrite
    protected boolean carveBlock(
            CarvingContext carvingContext,
            C carverConfiguration,
            ChunkAccess chunkAccess,
            Function<BlockPos, Holder<Biome>> function,
            CarvingMask unusedCarvingMask,
            BlockPos.MutableBlockPos mutableBlockPos,
            BlockPos.MutableBlockPos mutableBlockPos2,
            Aquifer aquifer,
            MutableBoolean mutableBoolean
    ) {
        CarverChunkWriter chunkWriter = this.ga$getCarverChunkWriter();
        int originalStateId = chunkWriter.getStateId(mutableBlockPos);
        CarveStateScratch carveStateScratch = this.ga$getCarveStateScratch();
        boolean restoreSurface = !carveStateScratch.isActive() || carveStateScratch.shouldRestoreSurface();
        if (restoreSurface && (originalStateId == GA$GRASS_STATE_ID || originalStateId == GA$MYCELIUM_STATE_ID)) {
            mutableBoolean.setTrue();
        }

        boolean debug = carveStateScratch.isActive() ? carveStateScratch.isDebug() : isDebugEnabled(carverConfiguration);
        if (!debug) {
            if (carveStateScratch.isActive()) {
                boolean[] replaceableStateIds = carveStateScratch.getReplaceableStateIds();
                if (originalStateId < 0 || originalStateId >= replaceableStateIds.length || !replaceableStateIds[originalStateId]) {
                    return false;
                }
            } else if (!this.canReplaceBlock(carverConfiguration, chunkWriter.getBlockState(mutableBlockPos))) {
                return false;
            }
        }

        int carvedStateId = this.ga$getDirectCarveStateId(carvingContext, carverConfiguration, chunkAccess, mutableBlockPos, aquifer, carveStateScratch, debug);
        BlockState carvedState;
        if (carvedStateId != GA$NO_DIRECT_STATE) {
            carvedState = carvedStateId == GA$CAVE_AIR_STATE_ID ? GA$CAVE_AIR_BLOCK : GA$LAVA_BLOCK;
        } else {
            carvedState = this.ga$getCarveState(carvingContext, carverConfiguration, mutableBlockPos, aquifer, debug);
            if (carvedState == null) {
                return false;
            }
            carvedStateId = GA$BlockStateExtension.get(carvedState).bts$getFastId();
        }

        if (chunkWriter.isActive()) {
            chunkWriter.setStateId(mutableBlockPos, carvedStateId, carvedState);
        } else {
            chunkAccess.setBlockState(mutableBlockPos, carvedState, false);
        }
        FluidState carvedFluid = carvedState.getFluidState();
        if (aquifer.shouldScheduleFluidUpdate() && !carvedFluid.isEmpty()) {
            if (chunkWriter.isActive()) {
                chunkWriter.markPosForPostprocessing(mutableBlockPos);
            } else {
                chunkAccess.markPosForPostprocessing(mutableBlockPos);
            }
        }

        if (restoreSurface && mutableBoolean.isTrue()) {
            mutableBlockPos2.setWithOffset(mutableBlockPos, net.minecraft.core.Direction.DOWN);
            if (chunkWriter.getStateId(mutableBlockPos2) == GA$DIRT_STATE_ID) {
                Optional<BlockState> topMaterial = carvingContext.topMaterial(function, chunkAccess, mutableBlockPos2, !carvedFluid.isEmpty());
                if (topMaterial.isPresent()) {
                    BlockState state = topMaterial.get();
                    if (chunkWriter.isActive()) {
                        chunkWriter.setBlockState(mutableBlockPos2, state);
                    } else {
                        chunkAccess.setBlockState(mutableBlockPos2, state, false);
                    }
                    if (!state.getFluidState().isEmpty()) {
                        if (chunkWriter.isActive()) {
                            chunkWriter.markPosForPostprocessing(mutableBlockPos2);
                        } else {
                            chunkAccess.markPosForPostprocessing(mutableBlockPos2);
                        }
                    }
                }
            }
        }

        return true;
    }

    @Unique
    private int ga$getDirectCarveStateId(
            CarvingContext carvingContext,
            C carverConfiguration,
            ChunkAccess chunkAccess,
            BlockPos blockPos,
            Aquifer aquifer,
            CarveStateScratch carveStateScratch,
            boolean debug
    ) {
        int lavaLevel = carveStateScratch.isActive() ? carveStateScratch.getLavaLevel() : carverConfiguration.lavaLevel.resolveY(carvingContext);
        if (blockPos.getY() <= lavaLevel) {
            return GA$LAVA_STATE_ID;
        }

        if (GA$FAST_SIMPLE_CAVE_STATE
                && !debug
                && !ga$isBelowGlobalWaterLevel(aquifer, blockPos)
                && !ga$isBelowSurfaceFluid(chunkAccess, blockPos)) {
            return GA$CAVE_AIR_STATE_ID;
        }

        return GA$NO_DIRECT_STATE;
    }

    @Unique
    private static boolean ga$isBelowGlobalWaterLevel(Aquifer aquifer, BlockPos blockPos) {
        if (!(aquifer instanceof MixinNoiseBasedAquiferAccessor accessor)) {
            return false;
        }

        Aquifer.FluidStatus fluidStatus = accessor.ga$getGlobalFluidPicker().computeFluid(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        if (blockPos.getY() >= fluidStatus.fluidLevel) {
            return false;
        }

        return fluidStatus.at(fluidStatus.fluidLevel - 1).is(Blocks.WATER);
    }

    @Unique
    private static boolean ga$isBelowSurfaceFluid(ChunkAccess chunkAccess, BlockPos blockPos) {
        int x = blockPos.getX() & 15;
        int z = blockPos.getZ() & 15;
        int surfaceY = chunkAccess.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        if (blockPos.getY() >= surfaceY) {
            return false;
        }

        return surfaceY > chunkAccess.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
    }

    @Unique
    private BlockState ga$getCarveState(
            CarvingContext carvingContext,
            C carverConfiguration,
            BlockPos blockPos,
            Aquifer aquifer,
            boolean debug
    ) {
        MutableFunctionContext functionContext = this.ga$getMutableFunctionContext();
        functionContext.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());

        BlockState state = aquifer.computeSubstance(functionContext, 0.0D);
        if (state == null) {
            return debug ? carverConfiguration.debugSettings.getBarrierState() : null;
        }

        return debug ? getDebugState(carverConfiguration, state) : state;
    }

    @Unique
    private MutableFunctionContext ga$getMutableFunctionContext() {
        ThreadLocal<MutableFunctionContext> context = this.ga$mutableFunctionContext;
        if (context == null) {
            context = ThreadLocal.withInitial(MutableFunctionContext::new);
            this.ga$mutableFunctionContext = context;
        }
        return context.get();
    }

    @Unique
    private CarveStateScratch ga$getCarveStateScratch() {
        ThreadLocal<CarveStateScratch> scratch = this.ga$carveStateScratch;
        if (scratch == null) {
            scratch = ThreadLocal.withInitial(CarveStateScratch::new);
            this.ga$carveStateScratch = scratch;
        }
        return scratch.get();
    }

    @Unique
    private CarverChunkWriter ga$getCarverChunkWriter() {
        ThreadLocal<CarverChunkWriter> writer = this.ga$carverChunkWriter;
        if (writer == null) {
            writer = ThreadLocal.withInitial(CarverChunkWriter::new);
            this.ga$carverChunkWriter = writer;
        }
        return writer.get();
    }
}
