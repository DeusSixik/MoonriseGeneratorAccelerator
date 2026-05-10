package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;

public final class ReusableTreeDecoratorContext extends TreeDecorator.Context {
    private static final int INITIAL_CAPACITY = 256;
    private static final int MAX_RETAINED_POSITIONS = 4_096;
    private static final Set<BlockPos> EMPTY_POSITIONS = Collections.emptySet();

    private LevelSimulatedReader level;
    private BiConsumer<BlockPos, BlockState> decorationSetter;
    private RandomSource random;
    private final ReusableBlockPosList logs = new ReusableBlockPosList();
    private final ReusableBlockPosList leaves = new ReusableBlockPosList();
    private final ReusableBlockPosList roots = new ReusableBlockPosList();

    public ReusableTreeDecoratorContext() {
        super(null, null, null, EMPTY_POSITIONS, EMPTY_POSITIONS, EMPTY_POSITIONS);
    }

    public void reset(
            LevelSimulatedReader level,
            BiConsumer<BlockPos, BlockState> decorationSetter,
            RandomSource random,
            LongArrayList logs,
            LongOpenHashSet leaves,
            LongArrayList roots
    ) {
        this.level = level;
        this.decorationSetter = decorationSetter;
        this.random = random;
        this.logs.load(logs);
        this.leaves.load(leaves);
        this.roots.load(roots);
    }

    public void clear() {
        this.level = null;
        this.decorationSetter = null;
        this.random = null;
        this.logs.clear();
        this.leaves.clear();
        this.roots.clear();
    }

    @Override
    public void setBlock(BlockPos pos, BlockState state) {
        this.decorationSetter.accept(pos, state);
    }

    @Override
    public boolean isAir(BlockPos pos) {
        return this.level.isStateAtPosition(pos, BlockState::isAir);
    }

    @Override
    public LevelSimulatedReader level() {
        return this.level;
    }

    @Override
    public RandomSource random() {
        return this.random;
    }

    @Override
    public ObjectArrayList<BlockPos> logs() {
        return this.logs.values;
    }

    @Override
    public ObjectArrayList<BlockPos> leaves() {
        return this.leaves.values;
    }

    @Override
    public ObjectArrayList<BlockPos> roots() {
        return this.roots.values;
    }

    private static final class ReusableBlockPosList {
        private ObjectArrayList<BlockPos> values = new ObjectArrayList<>(INITIAL_CAPACITY);
        private ObjectArrayList<BlockPos.MutableBlockPos> pool = new ObjectArrayList<>(INITIAL_CAPACITY);
        private Object[] sortBuffer = new Object[INITIAL_CAPACITY];
        private int[] yBuckets = new int[64];

        void load(LongArrayList packedPositions) {
            this.prepareForReuse();

            for (int i = 0; i < packedPositions.size(); i++) {
                this.addPacked(packedPositions.getLong(i));
            }

            this.sortByY();
        }

        void load(LongOpenHashSet packedPositions) {
            this.prepareForReuse();

            LongIterator iterator = packedPositions.iterator();
            while (iterator.hasNext()) {
                this.addPacked(iterator.nextLong());
            }

            this.sortByY();
        }

        void clear() {
            if (this.pool.size() > MAX_RETAINED_POSITIONS) {
                this.values = new ObjectArrayList<>(INITIAL_CAPACITY);
                this.pool = new ObjectArrayList<>(INITIAL_CAPACITY);
                this.sortBuffer = new Object[INITIAL_CAPACITY];
            } else if (this.sortBuffer.length > MAX_RETAINED_POSITIONS) {
                this.sortBuffer = new Object[INITIAL_CAPACITY];
            }

            if (this.yBuckets.length > 512) {
                this.yBuckets = new int[64];
            }

            this.values.clear();
        }

        private void prepareForReuse() {
            this.clear();
        }

        private void addPacked(long packedPos) {
            int index = this.values.size();
            BlockPos.MutableBlockPos mutablePos;

            if (index == this.pool.size()) {
                mutablePos = new BlockPos.MutableBlockPos();
                this.pool.add(mutablePos);
            } else {
                mutablePos = this.pool.get(index);
            }

            mutablePos.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));
            this.values.add(mutablePos);
        }

        private void sortByY() {
            int size = this.values.size();
            if (size <= 1) {
                return;
            }

            Object[] elements = this.values.elements();
            int minY = ((BlockPos) elements[0]).getY();
            int maxY = minY;

            for (int i = 1; i < size; i++) {
                int y = ((BlockPos) elements[i]).getY();
                if (y < minY) {
                    minY = y;
                } else if (y > maxY) {
                    maxY = y;
                }
            }

            int bucketCount = maxY - minY + 1;
            if (bucketCount > this.yBuckets.length) {
                this.yBuckets = new int[bucketCount];
            }

            if (size > this.sortBuffer.length) {
                this.sortBuffer = new Object[size];
            }

            Arrays.fill(this.yBuckets, 0, bucketCount, 0);
            for (int i = 0; i < size; i++) {
                this.yBuckets[((BlockPos) elements[i]).getY() - minY]++;
            }

            int offset = 0;
            for (int i = 0; i < bucketCount; i++) {
                int count = this.yBuckets[i];
                this.yBuckets[i] = offset;
                offset += count;
            }

            for (int i = 0; i < size; i++) {
                BlockPos pos = (BlockPos) elements[i];
                int bucket = pos.getY() - minY;
                this.sortBuffer[this.yBuckets[bucket]++] = pos;
            }

            System.arraycopy(this.sortBuffer, 0, elements, 0, size);
        }
    }
}
