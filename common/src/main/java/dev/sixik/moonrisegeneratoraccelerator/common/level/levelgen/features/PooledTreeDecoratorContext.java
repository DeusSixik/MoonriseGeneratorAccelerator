package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.features;

import dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.feature.TreeDecoratorContextAccessor;
import dev.sixik.moonrisegeneratoraccelerator.common.utils.pools.SimpleObjectPool;
import dev.sixik.moonrisegeneratoraccelerator.common.utils.pools.SimpleObjectPoolUnSynchronize;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;

import java.util.ArrayList;
import java.util.function.BiConsumer;

public class PooledTreeDecoratorContext extends TreeDecorator.Context {

    public static final ThreadLocal<SimpleObjectPool<PooledTreeDecoratorContext>> POOL = ThreadLocal.withInitial(() ->
            new SimpleObjectPoolUnSynchronize<>(
                    unused -> new PooledTreeDecoratorContext(),
                    null,
                    PooledTreeDecoratorContext::releaseData,
                    1024
            ));

    public PooledTreeDecoratorContext() {
        super(null, null, null, null, null, null);
    }

    public void reInit(WorldGenLevel level, BiConsumer<BlockPos, BlockState> setter, RandomSource random,
                       ObjectArrayList<BlockPos> logs, ObjectArrayList<BlockPos> leaves, ObjectArrayList<BlockPos> roots) {
        final var accessor = ((TreeDecoratorContextAccessor) this);
        accessor.setLevel(level);
        accessor.setDecorationSetter(setter);
        accessor.setRandom(random);
        accessor.setLogs(logs);
        accessor.setLeaves(leaves);
        accessor.setRoots(roots);
    }

    public void releaseData() {
        reInit(null, null, null, null, null, null);
    }
}
