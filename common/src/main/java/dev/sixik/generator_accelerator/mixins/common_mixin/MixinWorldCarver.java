package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
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

    @Shadow
    @Final
    protected static FluidState LAVA;

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
    private final ThreadLocal<GA$MutableFunctionContext> ga$mutableFunctionContext = ThreadLocal.withInitial(GA$MutableFunctionContext::new);

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
        BlockPos.MutableBlockPos carvePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
        MutableBoolean surfaceHit = new MutableBoolean();
        GA$CarvingMaskExtension maskExtension = (GA$CarvingMaskExtension) carvingMask;

        for (int localX = minX; localX <= maxX; ++localX) {
            int blockX = chunkPos.getBlockX(localX);
            double scaledX = ((double) blockX + 0.5D - d) / g;

            for (int localZ = minZ; localZ <= maxZ; ++localZ) {
                int blockZ = chunkPos.getBlockZ(localZ);
                double scaledZ = ((double) blockZ + 0.5D - f) / g;
                if (scaledX * scaledX + scaledZ * scaledZ >= 1.0D) {
                    continue;
                }

                surfaceHit.setFalse();
                for (int y = maxY; y > minY; --y) {
                    double scaledY = ((double) y - 0.5D - e) / h;
                    if (carveSkipChecker.shouldSkip(carvingContext, scaledX, scaledY, scaledZ, y)) {
                        continue;
                    }

                    if (!maskExtension.ga$setIfAbsent(localX, y, localZ)) {
                        if (!debug) {
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
                }
            }
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
        BlockState originalState = chunkAccess.getBlockState(mutableBlockPos);
        if (originalState.is(Blocks.GRASS_BLOCK) || originalState.is(Blocks.MYCELIUM)) {
            mutableBoolean.setTrue();
        }

        if (!this.canReplaceBlock(carverConfiguration, originalState) && !isDebugEnabled(carverConfiguration)) {
            return false;
        }

        BlockState carvedState = this.ga$getCarveState(carvingContext, carverConfiguration, mutableBlockPos, aquifer);
        if (carvedState == null) {
            return false;
        }

        chunkAccess.setBlockState(mutableBlockPos, carvedState, false);
        FluidState carvedFluid = carvedState.getFluidState();
        if (aquifer.shouldScheduleFluidUpdate() && !carvedFluid.isEmpty()) {
            chunkAccess.markPosForPostprocessing(mutableBlockPos);
        }

        if (mutableBoolean.isTrue()) {
            mutableBlockPos2.setWithOffset(mutableBlockPos, net.minecraft.core.Direction.DOWN);
            if (chunkAccess.getBlockState(mutableBlockPos2).is(Blocks.DIRT)) {
                Optional<BlockState> topMaterial = carvingContext.topMaterial(function, chunkAccess, mutableBlockPos2, !carvedFluid.isEmpty());
                if (topMaterial.isPresent()) {
                    BlockState state = topMaterial.get();
                    chunkAccess.setBlockState(mutableBlockPos2, state, false);
                    if (!state.getFluidState().isEmpty()) {
                        chunkAccess.markPosForPostprocessing(mutableBlockPos2);
                    }
                }
            }
        }

        return true;
    }

    @Unique
    private BlockState ga$getCarveState(CarvingContext carvingContext, C carverConfiguration, BlockPos blockPos, Aquifer aquifer) {
        if (blockPos.getY() <= carverConfiguration.lavaLevel.resolveY(carvingContext)) {
            return LAVA.createLegacyBlock();
        }

        GA$MutableFunctionContext functionContext = this.ga$mutableFunctionContext.get();
        functionContext.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());

        BlockState state = aquifer.computeSubstance(functionContext, 0.0D);
        if (state == null) {
            return isDebugEnabled(carverConfiguration) ? carverConfiguration.debugSettings.getBarrierState() : null;
        }

        return isDebugEnabled(carverConfiguration) ? getDebugState(carverConfiguration, state) : state;
    }

    @Unique
    private static final class GA$MutableFunctionContext implements DensityFunction.FunctionContext {
        private int x;
        private int y;
        private int z;

        void set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int blockX() {
            return this.x;
        }

        @Override
        public int blockY() {
            return this.y;
        }

        @Override
        public int blockZ() {
            return this.z;
        }
    }
}
