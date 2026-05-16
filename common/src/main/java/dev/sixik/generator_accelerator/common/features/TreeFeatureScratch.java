package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator_native_raw.structures.NativeBlockPosTracker;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

import java.util.function.BiConsumer;

public final class TreeFeatureScratch {
    private static final int INITIAL_CAPACITY = 256;
    private static final int MAX_RETAINED_POSITIONS = 4_096;

    private WorldGenLevel level;

    public final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    public final ReusableTreeDecoratorContext decoratorContext = new ReusableTreeDecoratorContext();
    public LongArrayList roots = new LongArrayList(INITIAL_CAPACITY);
    public LongArrayList trunks = new LongArrayList(INITIAL_CAPACITY);
    public NativeBlockPosTracker foliage = new NativeBlockPosTracker(INITIAL_CAPACITY);
    public LongArrayList decorators = new LongArrayList(INITIAL_CAPACITY);
    public final BiConsumer<BlockPos, BlockState> rootSetter = (pos, state) -> {
        this.roots.add(pos.asLong());
        this.level.setBlock(pos, state, 19);
    };
    public final BiConsumer<BlockPos, BlockState> trunkSetter = (pos, state) -> {
        this.trunks.add(pos.asLong());
        this.level.setBlock(pos, state, 19);
    };
    public final FoliagePlacer.FoliageSetter foliageSetter = new FoliagePlacer.FoliageSetter() {
        @Override
        public void set(BlockPos pos, BlockState state) {
            TreeFeatureScratch.this.foliage.add(pos);
            TreeFeatureScratch.this.level.setBlock(pos, state, 19);
        }

        @Override
        public boolean isSet(BlockPos pos) {
            return TreeFeatureScratch.this.foliage.contains(pos);
        }
    };
    public final BiConsumer<BlockPos, BlockState> decoratorSetter = (pos, state) -> {
        this.decorators.add(pos.asLong());
        this.level.setBlock(pos, state, 19);
    };

    public void begin(WorldGenLevel level) {
        this.level = level;
        this.roots = clearOrReset(this.roots);
        this.trunks = clearOrReset(this.trunks);
        this.decorators = clearOrReset(this.decorators);
        this.foliage = clearOrReset(this.foliage);
        this.decoratorContext.clear();
    }

    public TreeDecorator.Context prepareDecoratorContext(RandomSource random) {
        this.decoratorContext.reset(this.level, this.decoratorSetter, random, this.trunks, this.foliage, this.roots);
        return this.decoratorContext;
    }

    public void release() {
        this.level = null;
        this.decoratorContext.clear();
    }

    private static LongArrayList clearOrReset(LongArrayList list) {
        if (list.size() > MAX_RETAINED_POSITIONS) {
            return new LongArrayList(INITIAL_CAPACITY);
        }
        list.clear();
        return list;
    }

    private static NativeBlockPosTracker clearOrReset(NativeBlockPosTracker tracker) {
        if (tracker.recordedSize() > MAX_RETAINED_POSITIONS) {
            tracker.close();
            return new NativeBlockPosTracker(INITIAL_CAPACITY);
        }
        tracker.clear();
        return tracker;
    }
}
