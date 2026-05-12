package dev.sixik.generator_accelerator.common.features.mixin.features;

import dev.sixik.generator_accelerator.common.features.GAMultifaceSpreaderAccess;
import dev.sixik.generator_accelerator.common.features.GAMultifaceSpreadScratch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;

@Mixin(value = MultifaceSpreader.class, priority = 999)
public abstract class MixinMultifaceSpreader implements GAMultifaceSpreaderAccess {
    @Shadow
    @Final
    private MultifaceSpreader.SpreadConfig config;
    @Unique
    private static final Direction[] GA$DIRECTIONS = {
            Direction.DOWN,
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    @Unique
    private static final ThreadLocal<Direction[]> GA$SHUFFLED_FACES =
            ThreadLocal.withInitial(() -> new Direction[GA$DIRECTIONS.length]);
    @Unique
    private static final ThreadLocal<Direction[]> GA$SHUFFLED_DIRECTIONS =
            ThreadLocal.withInitial(() -> new Direction[GA$DIRECTIONS.length]);
    @Unique
    private static final ThreadLocal<GAMultifaceSpreadScratch> GA$SPREAD_SCRATCH =
            ThreadLocal.withInitial(GAMultifaceSpreadScratch::new);

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public boolean canSpreadInAnyDirection(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        Direction[] directions = GA$DIRECTIONS;
        for (int i = 0; i < directions.length; i++) {
            if (this.ga$getScratchSpreadFromFaceTowardDirection(state, level, pos, face, directions[i]) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public Optional<MultifaceSpreader.SpreadPos> spreadFromRandomFaceTowardRandomDirection(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            RandomSource random
    ) {
        Direction[] faces = ga$shuffledFaces(random);
        for (int i = 0; i < faces.length; i++) {
            Direction face = faces[i];
            if (!this.config.canSpreadFrom(state, face)) {
                continue;
            }
            MultifaceSpreader.SpreadPos spreadPos = this.ga$spreadFromFaceTowardRandomDirection(state, level, pos, face, random, false);
            if (spreadPos != null) {
                return Optional.of(ga$copySpreadPos(spreadPos));
            }
        }
        return Optional.empty();
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public long spreadAll(BlockState state, LevelAccessor level, BlockPos pos, boolean markForPostprocessing) {
        long count = 0L;
        Direction[] directions = GA$DIRECTIONS;
        for (int i = 0; i < directions.length; i++) {
            Direction face = directions[i];
            if (this.config.canSpreadFrom(state, face)) {
                count += this.ga$spreadFromFaceTowardAllDirections(state, level, pos, face, markForPostprocessing);
            }
        }
        return count;
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public Optional<MultifaceSpreader.SpreadPos> spreadFromFaceTowardRandomDirection(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            RandomSource random,
            boolean markForPostprocessing
    ) {
        MultifaceSpreader.SpreadPos spreadPos = this.ga$spreadFromFaceTowardRandomDirection(
                state,
                level,
                pos,
                face,
                random,
                markForPostprocessing
        );
        return spreadPos == null ? Optional.empty() : Optional.of(ga$copySpreadPos(spreadPos));
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public Optional<MultifaceSpreader.SpreadPos> spreadFromFaceTowardDirection(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            Direction direction,
            boolean markForPostprocessing
    ) {
        MultifaceSpreader.SpreadPos spreadPos = this.ga$getScratchSpreadFromFaceTowardDirection(
                state,
                level,
                pos,
                face,
                direction
        );
        if (spreadPos == null || !this.ga$spreadToFace(level, spreadPos, markForPostprocessing)) {
            return Optional.empty();
        }
        return Optional.of(ga$copySpreadPos(spreadPos));
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public Optional<MultifaceSpreader.SpreadPos> getSpreadFromFaceTowardDirection(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Direction face,
            Direction direction,
            MultifaceSpreader.SpreadPredicate spreadPredicate
    ) {
        MultifaceSpreader.SpreadPos spreadPos = this.ga$getScratchSpreadFromFaceTowardDirection(
                state,
                level,
                pos,
                face,
                direction,
                spreadPredicate
        );
        return spreadPos == null ? Optional.empty() : Optional.of(ga$copySpreadPos(spreadPos));
    }

    /**
     * @author Sixik
     * @reason Avoid stream/lambda/Optional churn in sculk and multiface worldgen spreading.
     */
    @Overwrite
    public Optional<MultifaceSpreader.SpreadPos> spreadToFace(
            LevelAccessor level,
            MultifaceSpreader.SpreadPos spreadPos,
            boolean markForPostprocessing
    ) {
        return this.ga$spreadToFace(level, spreadPos, markForPostprocessing) ? Optional.of(spreadPos) : Optional.empty();
    }

    @Unique
    @Nullable
    private MultifaceSpreader.SpreadPos ga$spreadFromFaceTowardRandomDirection(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            RandomSource random,
            boolean markForPostprocessing
    ) {
        Direction[] directions = ga$shuffledDirections(random);
        for (int i = 0; i < directions.length; i++) {
            MultifaceSpreader.SpreadPos spreadPos = this.ga$getScratchSpreadFromFaceTowardDirection(
                    state,
                    level,
                    pos,
                    face,
                    directions[i]
            );
            if (spreadPos != null && this.ga$spreadToFace(level, spreadPos, markForPostprocessing)) {
                return spreadPos;
            }
        }
        return null;
    }

    @Override
    public boolean ga$spreadFromFaceTowardRandomDirectionNoResult(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            RandomSource random,
            boolean markForPostprocessing
    ) {
        Direction[] directions = ga$shuffledDirections(random);
        for (int i = 0; i < directions.length; i++) {
            MultifaceSpreader.SpreadPos spreadPos = this.ga$getScratchSpreadFromFaceTowardDirection(
                    state,
                    level,
                    pos,
                    face,
                    directions[i]
            );
            if (spreadPos != null && this.ga$spreadToFace(level, spreadPos, markForPostprocessing)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private long ga$spreadFromFaceTowardAllDirections(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            boolean markForPostprocessing
    ) {
        long count = 0L;
        Direction[] directions = GA$DIRECTIONS;
        for (int i = 0; i < directions.length; i++) {
            MultifaceSpreader.SpreadPos spreadPos = this.ga$getScratchSpreadFromFaceTowardDirection(
                    state,
                    level,
                    pos,
                    face,
                    directions[i]
            );
            if (spreadPos != null && this.ga$spreadToFace(level, spreadPos, markForPostprocessing)) {
                count++;
            }
        }
        return count;
    }

    @Unique
    @Nullable
    private MultifaceSpreader.SpreadPos ga$getScratchSpreadFromFaceTowardDirection(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Direction face,
            Direction direction
    ) {
        if (direction.getAxis() == face.getAxis()) {
            return null;
        }
        if (!(this.config.isOtherBlockValidAsSource(state) || this.config.hasFace(state, face) && !this.config.hasFace(state, direction))) {
            return null;
        }

        MultifaceSpreader.SpreadType[] spreadTypes = this.config.getSpreadTypes();
        for (int i = 0; i < spreadTypes.length; i++) {
            MultifaceSpreader.SpreadPos spreadPos = ga$scratchSpreadPos(spreadTypes[i], pos, direction, face);
            if (this.config.canSpreadInto(level, pos, spreadPos)) {
                return spreadPos;
            }
        }
        return null;
    }

    @Unique
    @Nullable
    private MultifaceSpreader.SpreadPos ga$getScratchSpreadFromFaceTowardDirection(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Direction face,
            Direction direction,
            MultifaceSpreader.SpreadPredicate spreadPredicate
    ) {
        if (direction.getAxis() == face.getAxis()) {
            return null;
        }
        if (!(this.config.isOtherBlockValidAsSource(state) || this.config.hasFace(state, face) && !this.config.hasFace(state, direction))) {
            return null;
        }

        MultifaceSpreader.SpreadType[] spreadTypes = this.config.getSpreadTypes();
        for (int i = 0; i < spreadTypes.length; i++) {
            MultifaceSpreader.SpreadPos spreadPos = ga$scratchSpreadPos(spreadTypes[i], pos, direction, face);
            if (spreadPredicate.test(level, pos, spreadPos)) {
                return spreadPos;
            }
        }
        return null;
    }

    @Unique
    private boolean ga$spreadToFace(LevelAccessor level, MultifaceSpreader.SpreadPos spreadPos, boolean markForPostprocessing) {
        BlockState state = level.getBlockState(spreadPos.pos());
        return this.config.placeBlock(level, spreadPos, state, markForPostprocessing);
    }

    @Unique
    private static Direction[] ga$shuffledFaces(RandomSource random) {
        return ga$shuffleInto(GA$SHUFFLED_FACES.get(), random);
    }

    @Unique
    private static Direction[] ga$shuffledDirections(RandomSource random) {
        return ga$shuffleInto(GA$SHUFFLED_DIRECTIONS.get(), random);
    }

    @Unique
    private static Direction[] ga$shuffleInto(Direction[] out, RandomSource random) {
        System.arraycopy(GA$DIRECTIONS, 0, out, 0, GA$DIRECTIONS.length);
        for (int j = out.length; j > 1; --j) {
            int k = random.nextInt(j);
            Direction previous = out[j - 1];
            out[j - 1] = out[k];
            out[k] = previous;
        }
        return out;
    }

    @Unique
    private static MultifaceSpreader.SpreadPos ga$scratchSpreadPos(
            MultifaceSpreader.SpreadType spreadType,
            BlockPos pos,
            Direction direction,
            Direction face
    ) {
        if (spreadType == MultifaceSpreader.SpreadType.SAME_POSITION) {
            return GA$SPREAD_SCRATCH.get().set(pos, direction, 0, 0, 0);
        }
        if (spreadType == MultifaceSpreader.SpreadType.SAME_PLANE) {
            return GA$SPREAD_SCRATCH.get().set(
                    pos,
                    face,
                    direction.getStepX(),
                    direction.getStepY(),
                    direction.getStepZ()
            );
        }
        return GA$SPREAD_SCRATCH.get().set(
                pos,
                direction.getOpposite(),
                direction.getStepX() + face.getStepX(),
                direction.getStepY() + face.getStepY(),
                direction.getStepZ() + face.getStepZ()
        );
    }

    @Unique
    private static MultifaceSpreader.SpreadPos ga$copySpreadPos(MultifaceSpreader.SpreadPos spreadPos) {
        return new MultifaceSpreader.SpreadPos(spreadPos.pos().immutable(), spreadPos.face());
    }

}
