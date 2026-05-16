package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.fluid.GAFluidSpreadCache;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

@Mixin(value = FlowingFluid.class, priority = 999)
public abstract class MixinFlowingFluid$occlusion_cache {
    @Unique
    private static final int GA$CACHE_SIZE = 200;
    @Unique
    private static final byte GA$CACHE_MISS = 127;

    @Unique
    private static final Direction[] GA$HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };

    @Unique
    private static final ThreadLocal<Long2ByteLinkedOpenHashMap> GA$OCCLUSION_CACHE = ThreadLocal.withInitial(() -> {
        Long2ByteLinkedOpenHashMap cache = new Long2ByteLinkedOpenHashMap(GA$CACHE_SIZE) {
            @Override
            protected void rehash(int n) {
                // Vanilla keeps this tiny LRU cache fixed-size; do the same without object keys.
            }
        };
        cache.defaultReturnValue(GA$CACHE_MISS);
        return cache;
    });

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$FLUID_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPREAD_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SPREAD_BELOW_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos[]> GA$SLOPE_POSITIONS =
            ThreadLocal.withInitial(() -> ga$newMutablePosArray(8));
    @Unique
    private static final ThreadLocal<EnumMap<Direction, FluidState>> GA$SPREAD_RESULT =
            ThreadLocal.withInitial(() -> new EnumMap<>(Direction.class));
    @Unique
    private static final ThreadLocal<FluidState[]> GA$SPREAD_FLUIDS =
            ThreadLocal.withInitial(() -> new FluidState[GA$HORIZONTAL_DIRECTIONS.length]);
    @Unique
    private static final ThreadLocal<BlockState[]> GA$SPREAD_STATES =
            ThreadLocal.withInitial(() -> new BlockState[GA$HORIZONTAL_DIRECTIONS.length]);
    @Unique
    private static final ThreadLocal<GAFluidSpreadCache> GA$SPREAD_CACHE =
            ThreadLocal.withInitial(GAFluidSpreadCache::new);
    @Unique
    private FluidState ga$sourceStill;
    @Unique
    private FluidState ga$sourceFalling;
    @Unique
    private final FluidState[] ga$flowingStill = new FluidState[9];
    @Unique
    private final FluidState[] ga$flowingFalling = new FluidState[9];

    @Shadow
    public abstract FluidState getFlowing(int amount, boolean falling);

    @Shadow
    public abstract Fluid getFlowing();

    @Shadow
    public abstract FluidState getSource(boolean falling);

    @Shadow
    protected abstract boolean canConvertToSource(Level level);

    @Shadow
    protected abstract int getDropOff(LevelReader level);

    @Shadow
    protected abstract int getSlopeFindDistance(LevelReader level);

    @Shadow
    protected abstract boolean canSpreadTo(
            BlockGetter level,
            BlockPos fromPos,
            BlockState fromState,
            Direction direction,
            BlockPos toPos,
            BlockState toState,
            FluidState toFluid,
            Fluid fluid
    );

    @Shadow
    protected abstract void spreadTo(
            LevelAccessor level,
            BlockPos pos,
            BlockState state,
            Direction direction,
            FluidState fluidState
    );

    @Shadow
    private boolean canPassThrough(
            BlockGetter level,
            net.minecraft.world.level.material.Fluid fluid,
            BlockPos fromPos,
            BlockState fromState,
            Direction direction,
            BlockPos toPos,
            BlockState toState,
            FluidState toFluid
    ) {
        throw new RuntimeException();
    }

    @Shadow
    private boolean isWaterHole(
            BlockGetter level,
            net.minecraft.world.level.material.Fluid fluid,
            BlockPos fromPos,
            BlockState fromState,
            BlockPos toPos,
            BlockState toState
    ) {
        throw new RuntimeException();
    }

    /**
     * @author Sixik
     * @reason Reuse the per-fluid spread caches and positions instead of allocating three maps
     * and multiple adjacent BlockPos objects for every scheduled fluid tick.
     */
    @Overwrite
    protected Map<Direction, FluidState> getSpread(Level level, BlockPos pos, BlockState state) {
        EnumMap<Direction, FluidState> result = GA$SPREAD_RESULT.get();
        result.clear();

        FluidState[] spreadFluids = GA$SPREAD_FLUIDS.get();
        BlockState[] spreadStates = GA$SPREAD_STATES.get();
        int spreadMask = this.ga$computeSpread(level, pos, state, spreadFluids, spreadStates);
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            if ((spreadMask & (1 << i)) != 0 && spreadFluids[i] != null) {
                result.put(GA$HORIZONTAL_DIRECTIONS[i], spreadFluids[i]);
            }
        }
        return result;
    }

    @Unique
    private int ga$computeSpread(
            Level level,
            BlockPos pos,
            BlockState state,
            FluidState[] spreadFluids,
            BlockState[] spreadStates
    ) {
        int bestDistance = 1000;
        int spreadMask = 0;
        GAFluidSpreadCache cache = GA$SPREAD_CACHE.get();
        cache.clear();
        Fluid flowing = this.getFlowing();

        BlockPos.MutableBlockPos sidePos = GA$SPREAD_POS.get();
        BlockPos.MutableBlockPos belowPos = GA$SPREAD_BELOW_POS.get();
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            spreadFluids[i] = null;
            spreadStates[i] = null;
            Direction direction = GA$HORIZONTAL_DIRECTIONS[i];
            sidePos.setWithOffset(pos, direction);
            short cacheKey = ga$getCacheKey(pos, sidePos);
            BlockState sideState = ga$cachedState(level, sidePos, cacheKey, cache.states);
            FluidState sideFluid = sideState.getFluidState();
            if (!this.canPassThrough(
                    level,
                    flowing,
                    pos,
                    state,
                    direction,
                    sidePos,
                    sideState,
                    sideFluid
            )) {
                continue;
            }

            belowPos.setWithOffset(sidePos, Direction.DOWN);
            boolean waterHole = ga$cachedWaterHole(
                    level,
                    flowing,
                    sidePos,
                    sideState,
                    belowPos,
                    cacheKey,
                    cache.holes
            );
            int distance = waterHole
                    ? 0
                    : this.ga$getSlopeDistanceFast(level, sidePos, 1, direction.getOpposite(), sideState, pos, cache);
            if (distance < bestDistance) {
                spreadMask = 0;
                bestDistance = distance;
            }
            if (distance <= bestDistance) {
                FluidState newFluid = ga$fluidOrEmpty(this.getNewLiquid(level, sidePos, sideState));
                spreadFluids[i] = newFluid;
                spreadStates[i] = sideState;
                spreadMask |= 1 << i;
            }
        }
        return spreadMask;
    }

    /**
     * @author Sixik
     * @reason Remove recursive BlockPos allocation and lambda-backed map lookups from
     * vanilla fluid slope probing.
     */
    @Overwrite
    protected int getSlopeDistance(
            LevelReader level,
            BlockPos pos,
            int distance,
            Direction sourceDirection,
            BlockState state,
            BlockPos source,
            Short2ObjectMap<BlockState> stateCache,
            Short2BooleanMap holeCache
    ) {
        state = ga$stateOrAir(state);
        int bestDistance = 1000;
        Fluid flowing = this.getFlowing();
        int maxDistance = this.getSlopeFindDistance(level);
        BlockPos.MutableBlockPos belowPos = GA$SPREAD_BELOW_POS.get();
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            Direction direction = GA$HORIZONTAL_DIRECTIONS[i];
            if (direction == sourceDirection) {
                continue;
            }

            BlockPos.MutableBlockPos sidePos = ga$slopePos(distance).setWithOffset(pos, direction);
            short cacheKey = ga$getCacheKey(source, sidePos);
            BlockState sideState = ga$cachedState(level, sidePos, cacheKey, stateCache);
            FluidState sideFluid = sideState.getFluidState();
            if (!this.canPassThrough(level, flowing, pos, state, direction, sidePos, sideState, sideFluid)) {
                continue;
            }

            boolean waterHole = ga$cachedWaterHole(
                    level,
                    flowing,
                    sidePos,
                    sideState,
                    belowPos.setWithOffset(sidePos, Direction.DOWN),
                    cacheKey,
                    holeCache
            );
            if (waterHole) {
                return distance;
            }
            if (distance < maxDistance) {
                int recursiveDistance = this.getSlopeDistance(
                        level,
                        sidePos,
                        distance + 1,
                        direction.getOpposite(),
                        sideState,
                        source,
                        stateCache,
                        holeCache
                );
                if (recursiveDistance < bestDistance) {
                    bestDistance = recursiveDistance;
                }
            }
        }
        return bestDistance;
    }

    @Unique
    private int ga$getSlopeDistanceFast(
            LevelReader level,
            BlockPos pos,
            int distance,
            Direction sourceDirection,
            BlockState state,
            BlockPos source,
            GAFluidSpreadCache cache
    ) {
        state = ga$stateOrAir(state);
        int bestDistance = 1000;
        Fluid flowing = this.getFlowing();
        int maxDistance = this.getSlopeFindDistance(level);
        BlockPos.MutableBlockPos belowPos = GA$SPREAD_BELOW_POS.get();
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            Direction direction = GA$HORIZONTAL_DIRECTIONS[i];
            if (direction == sourceDirection) {
                continue;
            }

            BlockPos.MutableBlockPos sidePos = ga$slopePos(distance).setWithOffset(pos, direction);
            short cacheKey = ga$getCacheKey(source, sidePos);
            BlockState sideState = ga$cachedState(level, sidePos, cacheKey, cache.states);
            FluidState sideFluid = sideState.getFluidState();
            if (!this.canPassThrough(level, flowing, pos, state, direction, sidePos, sideState, sideFluid)) {
                continue;
            }

            boolean waterHole = ga$cachedWaterHole(
                    level,
                    flowing,
                    sidePos,
                    sideState,
                    belowPos.setWithOffset(sidePos, Direction.DOWN),
                    cacheKey,
                    cache.holes
            );
            if (waterHole) {
                return distance;
            }
            if (distance < maxDistance) {
                int recursiveDistance = this.ga$getSlopeDistanceFast(
                        level,
                        sidePos,
                        distance + 1,
                        direction.getOpposite(),
                        sideState,
                        source,
                        cache
                );
                if (recursiveDistance < bestDistance) {
                    bestDistance = recursiveDistance;
                }
            }
        }
        return bestDistance;
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos.below allocation in every scheduled fluid tick.
     */
    @Overwrite
    protected void spread(Level level, BlockPos pos, FluidState state) {
        if (state.isEmpty()) {
            return;
        }

        BlockState blockState = ga$stateOrAir(level.getBlockState(pos));
        BlockPos.MutableBlockPos belowPos = GA$SPREAD_BELOW_POS.get().setWithOffset(pos, Direction.DOWN);
        BlockState belowState = ga$stateOrAir(level.getBlockState(belowPos));
        FluidState newFluid = ga$fluidOrEmpty(this.getNewLiquid(level, belowPos, belowState));
        Fluid newFluidType = newFluid.getType();
        if (this.canSpreadTo(level, pos, blockState, Direction.DOWN, belowPos, belowState, belowState.getFluidState(), newFluidType)) {
            this.spreadTo(level, belowPos, belowState, Direction.DOWN, newFluid);
            if (this.sourceNeighborCount(level, pos) >= 3) {
                this.spreadToSides(level, pos, state, blockState);
            }
            return;
        }

        if (state.isSource() || !this.isWaterHole(level, newFluidType, pos, blockState, belowPos, belowState)) {
            this.spreadToSides(level, pos, state, blockState);
        }
    }

    /**
     * @author Sixik
     * @reason Iterate spread directions directly and reuse one mutable target position instead
     * of allocating BlockPos.relative plus EnumMap iterators.
     */
    @Overwrite
    private void spreadToSides(Level level, BlockPos pos, FluidState state, BlockState blockState) {
        int sideAmount = state.getAmount() - this.getDropOff(level);
        if (state.getValue(BlockStateProperties.FALLING)) {
            sideAmount = 7;
        }
        if (sideAmount <= 0) {
            return;
        }

        FluidState[] spreadFluids = GA$SPREAD_FLUIDS.get();
        BlockState[] spreadStates = GA$SPREAD_STATES.get();
        int spreadMask = this.ga$computeSpread(level, pos, blockState, spreadFluids, spreadStates);
        if (spreadMask == 0) {
            return;
        }

        BlockPos.MutableBlockPos sidePos = GA$SPREAD_POS.get();
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            if ((spreadMask & (1 << i)) == 0) {
                continue;
            }
            Direction direction = GA$HORIZONTAL_DIRECTIONS[i];
            FluidState targetFluid = spreadFluids[i];
            if (targetFluid == null) {
                continue;
            }
            sidePos.setWithOffset(pos, direction);
            BlockState sideState = spreadStates[i];
            if (sideState == null) {
                sideState = ga$stateOrAir(level.getBlockState(sidePos));
            }
            if (this.canSpreadTo(level, pos, blockState, direction, sidePos, sideState, sideState.getFluidState(), targetFluid.getType())) {
                this.spreadTo(level, sidePos, sideState, direction, targetFluid);
            }
        }
    }

    /**
     * @author Sixik
     * @reason Avoid short-lived BlockPos allocations while preserving vanilla fluid level calculation.
     */
    @Overwrite
    protected FluidState getNewLiquid(Level level, BlockPos pos, BlockState state) {
        state = ga$stateOrAir(state);
        BlockPos.MutableBlockPos cursor = GA$FLUID_POS.get();
        int amount = 0;
        int sourceCount = 0;
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            Direction direction = GA$HORIZONTAL_DIRECTIONS[i];
            cursor.setWithOffset(pos, direction);
            BlockState sideState = ga$stateOrAir(level.getBlockState(cursor));
            FluidState sideFluid = sideState.getFluidState();
            if (!sideFluid.getType().isSame((FlowingFluid) (Object) this)
                    || !this.canPassThroughWall(direction, level, pos, state, cursor, sideState)) {
                continue;
            }
            if (sideFluid.isSource()) {
                sourceCount++;
            }
            amount = Math.max(amount, sideFluid.getAmount());
        }

        if (this.canConvertToSource(level) && sourceCount >= 2) {
            cursor.setWithOffset(pos, Direction.DOWN);
            BlockState belowState = ga$stateOrAir(level.getBlockState(cursor));
            FluidState belowFluid = belowState.getFluidState();
            if (belowState.isSolid() || this.ga$isSourceBlockOfThisType(belowFluid)) {
                return this.ga$getSourceCached(false);
            }
        }

        cursor.setWithOffset(pos, Direction.UP);
        BlockState aboveState = ga$stateOrAir(level.getBlockState(cursor));
        FluidState aboveFluid = aboveState.getFluidState();
        if (!aboveFluid.isEmpty()
                && aboveFluid.getType().isSame((FlowingFluid) (Object) this)
                && this.canPassThroughWall(Direction.UP, level, pos, state, cursor, aboveState)) {
            return this.ga$getFlowingCached(8, true);
        }

        int flowingAmount = amount - this.getDropOff(level);
        return flowingAmount <= 0 ? Fluids.EMPTY.defaultFluidState() : this.ga$getFlowingCached(flowingAmount, false);
    }

    /**
     * @author Sixik
     * @reason Avoid BlockPos allocation in the downward spread source-neighbor check.
     */
    @Overwrite
    private int sourceNeighborCount(LevelReader level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = GA$FLUID_POS.get();
        int count = 0;
        for (int i = 0; i < GA$HORIZONTAL_DIRECTIONS.length; i++) {
            cursor.setWithOffset(pos, GA$HORIZONTAL_DIRECTIONS[i]);
            if (this.ga$isSourceBlockOfThisType(ga$stateOrAir(level.getBlockState(cursor)).getFluidState())) {
                count++;
            }
        }
        return count;
    }

    /**
     * @author Sixik
     * @reason Preserve vanilla fluid occlusion semantics while avoiding BlockStatePairKey allocation on every lookup.
     */
    @Overwrite
    private boolean canPassThroughWall(
            Direction direction,
            BlockGetter level,
            BlockPos fromPos,
            BlockState fromState,
            BlockPos toPos,
            BlockState toState
    ) {
        fromState = ga$stateOrAir(fromState);
        toState = ga$stateOrAir(toState);
        Long2ByteLinkedOpenHashMap cache = fromState.getBlock().hasDynamicShape() || toState.getBlock().hasDynamicShape()
                ? null
                : GA$OCCLUSION_CACHE.get();
        long key = 0L;
        if (cache != null) {
            key = ga$occlusionKey(fromState, toState, direction);
            byte cached = cache.getAndMoveToFirst(key);
            if (cached != GA$CACHE_MISS) {
                return cached != 0;
            }
        }

        VoxelShape fromShape = fromState.getCollisionShape(level, fromPos);
        VoxelShape toShape = toState.getCollisionShape(level, toPos);
        boolean canPass = !Shapes.mergedFaceOccludes(fromShape, toShape, direction);
        if (cache != null) {
            if (cache.size() == GA$CACHE_SIZE) {
                cache.removeLastByte();
            }
            cache.putAndMoveToFirst(key, (byte) (canPass ? 1 : 0));
        }
        return canPass;
    }

    @Unique
    private static long ga$occlusionKey(BlockState fromState, BlockState toState, Direction direction) {
        long fromId = GA$BlockStateExtension.get(fromState).bts$getFastId() & 0xFFFF_FFFFL;
        long toId = GA$BlockStateExtension.get(toState).bts$getFastId() & 0xFFFF_FFFFL;
        return (fromId << 32) ^ (toId << 4) ^ (long) direction.ordinal();
    }

    @Unique
    private static short ga$getCacheKey(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        return (short) (((dx + 128) & 255) << 8 | ((dz + 128) & 255));
    }

    @Unique
    private static BlockState ga$cachedState(
            LevelReader level,
            BlockPos pos,
            short key,
            Short2ObjectMap<BlockState> cache
    ) {
        BlockState state = cache.get(key);
        if (state != null) {
            return state;
        }
        state = level.getBlockState(pos);
        state = ga$stateOrAir(state);
        cache.put(key, state);
        return state;
    }

    @Unique
    private static BlockState ga$cachedState(
            LevelReader level,
            BlockPos pos,
            short key,
            GAFluidSpreadCache.StateCache cache
    ) {
        BlockState state = cache.get(key);
        if (state != null) {
            return state;
        }
        state = level.getBlockState(pos);
        state = ga$stateOrAir(state);
        cache.put(key, state);
        return state;
    }

    @Unique
    private boolean ga$cachedWaterHole(
            BlockGetter level,
            Fluid fluid,
            BlockPos sidePos,
            BlockState sideState,
            BlockPos belowPos,
            short key,
            Short2BooleanMap cache
    ) {
        sideState = ga$stateOrAir(sideState);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        boolean waterHole = this.isWaterHole(level, fluid, sidePos, sideState, belowPos, ga$stateOrAir(level.getBlockState(belowPos)));
        cache.put(key, waterHole);
        return waterHole;
    }

    @Unique
    private boolean ga$cachedWaterHole(
            BlockGetter level,
            Fluid fluid,
            BlockPos sidePos,
            BlockState sideState,
            BlockPos belowPos,
            short key,
            GAFluidSpreadCache.BooleanCache cache
    ) {
        sideState = ga$stateOrAir(sideState);
        int index = cache.indexOf(key);
        if (index >= 0) {
            return cache.valueAt(index);
        }
        boolean waterHole = this.isWaterHole(level, fluid, sidePos, sideState, belowPos, ga$stateOrAir(level.getBlockState(belowPos)));
        cache.put(key, waterHole);
        return waterHole;
    }

    @Unique
    private static BlockState ga$stateOrAir(BlockState state) {
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Unique
    private static FluidState ga$fluidOrEmpty(FluidState fluidState) {
        return fluidState == null ? Fluids.EMPTY.defaultFluidState() : fluidState;
    }

    @Unique
    private boolean ga$isSourceBlockOfThisType(FluidState fluidState) {
        return fluidState != null && fluidState.getType().isSame((FlowingFluid) (Object) this) && fluidState.isSource();
    }

    @Unique
    private FluidState ga$getSourceCached(boolean falling) {
        FluidState cached = falling ? this.ga$sourceFalling : this.ga$sourceStill;
        if (cached != null) {
            return cached;
        }
        cached = this.getSource(falling);
        if (falling) {
            this.ga$sourceFalling = cached;
        } else {
            this.ga$sourceStill = cached;
        }
        return cached;
    }

    @Unique
    private FluidState ga$getFlowingCached(int amount, boolean falling) {
        if (amount >= 0 && amount < this.ga$flowingStill.length) {
            FluidState[] cache = falling ? this.ga$flowingFalling : this.ga$flowingStill;
            FluidState cached = cache[amount];
            if (cached != null) {
                return cached;
            }
            cached = this.getFlowing(amount, falling);
            cache[amount] = cached;
            return cached;
        }
        return this.getFlowing(amount, falling);
    }

    @Unique
    private static BlockPos.MutableBlockPos ga$slopePos(int depth) {
        BlockPos.MutableBlockPos[] positions = GA$SLOPE_POSITIONS.get();
        if (depth >= positions.length) {
            int newLength = positions.length;
            while (depth >= newLength) {
                newLength <<= 1;
            }
            positions = Arrays.copyOf(positions, newLength);
            for (int i = 0; i < positions.length; i++) {
                if (positions[i] == null) {
                    positions[i] = new BlockPos.MutableBlockPos();
                }
            }
            GA$SLOPE_POSITIONS.set(positions);
        }
        return positions[depth];
    }

    @Unique
    private static BlockPos.MutableBlockPos[] ga$newMutablePosArray(int size) {
        BlockPos.MutableBlockPos[] positions = new BlockPos.MutableBlockPos[size];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = new BlockPos.MutableBlockPos();
        }
        return positions;
    }
}
