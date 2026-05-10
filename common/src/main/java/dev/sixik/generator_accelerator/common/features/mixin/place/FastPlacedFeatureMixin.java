package dev.sixik.generator_accelerator.common.features.mixin.place;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.*;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;


@Deprecated
@Mixin(PlacedFeature.class)
public class FastPlacedFeatureMixin {

    @Shadow @Final private List<PlacementModifier> placement;
    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;

    @Unique
    private static final ThreadLocal<PlacementRecursionScratch> GA$SCRATCH =
            ThreadLocal.withInitial(PlacementRecursionScratch::new);

    /**
     * @author Sixik
     * @reason Eliminating Stream.flatMap overhead while preserving vanilla Depth-First ordering
     */
    @Overwrite
    public final boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos pos) {
        return this.bts$placeRecursively(context, random, pos, 0);
    }

    @Unique
    private boolean bts$placeRecursively(PlacementContext context, RandomSource random, BlockPos pos, int modifierIndex) {
        if (modifierIndex >= this.placement.size()) {
            final ConfiguredFeature<?, ? extends Feature<?>> feature = this.feature.value();
            return feature.place(context.getLevel(), context.generator(), random, pos);
        }

        PlacementModifier modifier = this.placement.get(modifierIndex);
        if (modifier instanceof GA$PlacementModifierExtension extension && extension.ga$hasFastPositions()) {
            PlacementRecursionScratch scratch = GA$SCRATCH.get();
            boolean success = false;
            LongScratchBuffer positions = scratch.acquireBuffer();
            try {
                int positionIndex = scratch.activeIndex();
                extension.generatePositionsRaw(context, random, pos.asLong(), positions);
                long[] packedPositions = positions.elements();
                for (int i = 0, size = positions.size(); i < size; i++) {
                    BlockPos.MutableBlockPos nextPos = scratch.mutablePos(positionIndex).set(packedPositions[i]);
                    if (this.bts$placeRecursively(context, random, nextPos, modifierIndex + 1)) {
                        success = true;
                    }
                }
            } finally {
                scratch.releaseBuffer();
            }
            return success;
        }

        boolean success = false;
        try (Stream<BlockPos> stream = modifier.getPositions(context, random, pos)) {
            Iterator<BlockPos> iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (this.bts$placeRecursively(context, random, iterator.next(), modifierIndex + 1)) {
                    success = true;
                }
            }
        }
        return success;
    }

    @Unique
    private static final class PlacementRecursionScratch {
        private static final int INITIAL_CAPACITY = 4;
        private static final int DEFAULT_BUFFER_CAPACITY = 32;

        private LongScratchBuffer[] buffers = new LongScratchBuffer[INITIAL_CAPACITY];
        private BlockPos.MutableBlockPos[] positions = new BlockPos.MutableBlockPos[INITIAL_CAPACITY];
        private int depth;

        LongScratchBuffer acquireBuffer() {
            int index = this.depth;
            this.ensureCapacity(index);
            LongScratchBuffer buffer = this.buffers[index];
            if (buffer == null) {
                buffer = new LongScratchBuffer(DEFAULT_BUFFER_CAPACITY);
                this.buffers[index] = buffer;
            } else {
                buffer.clear();
            }
            this.depth = index + 1;
            return buffer;
        }

        void releaseBuffer() {
            if (this.depth <= 0) {
                return;
            }
            int index = this.depth - 1;
            LongScratchBuffer buffer = this.buffers[index];
            if (buffer != null) {
                buffer.clear();
            }
            this.depth = index;
        }

        int activeIndex() {
            return Math.max(0, this.depth - 1);
        }

        BlockPos.MutableBlockPos mutablePos(int index) {
            this.ensureCapacity(index);
            BlockPos.MutableBlockPos pos = this.positions[index];
            if (pos == null) {
                pos = new BlockPos.MutableBlockPos();
                this.positions[index] = pos;
            }
            return pos;
        }

        private void ensureCapacity(int index) {
            if (index < this.buffers.length) {
                return;
            }
            int newLength = this.buffers.length;
            while (index >= newLength) {
                newLength <<= 1;
            }
            LongScratchBuffer[] newBuffers = new LongScratchBuffer[newLength];
            System.arraycopy(this.buffers, 0, newBuffers, 0, this.buffers.length);
            this.buffers = newBuffers;
            BlockPos.MutableBlockPos[] newPositions = new BlockPos.MutableBlockPos[newLength];
            System.arraycopy(this.positions, 0, newPositions, 0, this.positions.length);
            this.positions = newPositions;
        }
    }
}
