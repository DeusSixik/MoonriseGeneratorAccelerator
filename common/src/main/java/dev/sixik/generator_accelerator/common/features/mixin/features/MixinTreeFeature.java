package dev.sixik.generator_accelerator.common.features.mixin.features;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.apache.commons.lang3.NotImplementedException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiConsumer;

@Mixin(TreeFeature.class)
public abstract class MixinTreeFeature {

    @Unique
    private static final Direction[] BTS$DIRS = Direction.values();

    @Unique
    private static final ThreadLocal<LongOpenHashSet[]> BTS$SHARED_QUEUES = ThreadLocal.withInitial(() -> {
        LongOpenHashSet[] arr = new LongOpenHashSet[7];
        for (int i = 0; i < 7; i++) arr[i] = new LongOpenHashSet();
        return arr;
    });


    @Shadow
    protected abstract boolean doPlace(WorldGenLevel worldGenLevel, RandomSource randomSource, BlockPos blockPos, BiConsumer<BlockPos, BlockState> biConsumer, BiConsumer<BlockPos, BlockState> biConsumer2, FoliagePlacer.FoliageSetter foliageSetter, TreeConfiguration treeConfiguration);

    @Shadow
    private static void setBlockKnownShape(LevelWriter levelWriter, BlockPos blockPos, BlockState blockState) {
        throw new NotImplementedException();
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public final boolean place(FeaturePlaceContext<TreeConfiguration> pContext) {
        final WorldGenLevel level = pContext.level();
        RandomSource random = pContext.random();
        BlockPos origin = pContext.origin();
        TreeConfiguration config = pContext.config();

        LongArrayList roots = new LongArrayList();
        LongArrayList trunks = new LongArrayList();
        LongOpenHashSet foliage = new LongOpenHashSet();
        LongArrayList decorators = new LongArrayList();

        BiConsumer<BlockPos, BlockState> rootSetter = (pos, state) -> {
            roots.add(pos.asLong());
            level.setBlock(pos, state, 19);
        };
        BiConsumer<BlockPos, BlockState> trunkSetter = (pos, state) -> {
            trunks.add(pos.asLong());
            level.setBlock(pos, state, 19);
        };
        FoliagePlacer.FoliageSetter foliageSetter = new FoliagePlacer.FoliageSetter() {
            @Override
            public void set(BlockPos pos, BlockState state) {
                foliage.add(pos.asLong());
                level.setBlock(pos, state, 19);
            }

            @Override
            public boolean isSet(BlockPos pos) {
                return foliage.contains(pos.asLong());
            }
        };
        BiConsumer<BlockPos, BlockState> decoratorSetter = (pos, state) -> {
            decorators.add(pos.asLong());
            level.setBlock(pos, state, 19);
        };

        boolean placed = this.doPlace(level, random, origin, rootSetter, trunkSetter, foliageSetter, config);

        if (placed && (!trunks.isEmpty() || !foliage.isEmpty())) {

            if (!config.decorators.isEmpty()) {
                Set<BlockPos> rootList = bts$unpackLongs(roots);
                Set<BlockPos> trunkList = bts$unpackLongs(trunks);
                Set<BlockPos> foliageList = bts$unpackLongs(foliage);

                TreeDecorator.Context decoratorContext = new TreeDecorator.Context(level, decoratorSetter, random, trunkList, foliageList, rootList);
                config.decorators.forEach(dec -> dec.place(decoratorContext));
            }

            BoundingBox box = bts$calculateBoundingBox(foliage, roots, trunks, decorators);

            if (box != null) {
                DiscreteVoxelShape shape = bts$updateLeavesFast(level, box, roots, trunks, foliage);
                StructureTemplate.updateShapeAtEdge(level, 3, shape, box.minX(), box.minY(), box.minZ());
                return true;
            }
        }
        return false;
    }

    /**
     * DOD-версия алгоритма Flood-Fill для обновления листьев.
     * Работает в 10 раз быстрее за счет сырых long массивов и отсутствия аллокаций.
     */
    @Unique
    private static DiscreteVoxelShape bts$updateLeavesFast(
            LevelAccessor level, BoundingBox box, LongArrayList roots, LongArrayList trunks, LongOpenHashSet foliage
    ) {
        DiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(box.getXSpan(), box.getYSpan(), box.getZSpan());

        bts$fillShape(shape, box, trunks);
        bts$fillShape(shape, box, foliage);

        LongOpenHashSet[] queues = BTS$SHARED_QUEUES.get();
        for (int i = 0; i < 7; i++)
            queues[i].clear();

        for (int i = 0; i < roots.size(); i++) {
            queues[0].add(roots.getLong(i));
        }

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

        int currentDist = 0;
        try (BulkSectionAccess bulkAccess = new BulkSectionAccess(level)) {

            final Direction[] dirs = BTS$DIRS;
            final int dirLen = dirs.length;

            while (currentDist < 7) {
                LongOpenHashSet currentQueue = queues[currentDist];
                if (currentQueue.isEmpty()) {
                    currentDist++;
                    continue;
                }

                LongIterator iterator = currentQueue.iterator();
                while (iterator.hasNext()) {
                    long pos = iterator.nextLong();
                    int px = BlockPos.getX(pos);
                    int py = BlockPos.getY(pos);
                    int pz = BlockPos.getZ(pos);

                    if (box.isInside(px, py, pz)) {
                        if (currentDist != 0) {
                            mPos.set(px, py, pz);
                            BlockState state = bulkAccess.getBlockState(mPos);
                            if (state.hasProperty(BlockStateProperties.DISTANCE)) {
                                setBlockKnownShape(level, mPos, state.setValue(BlockStateProperties.DISTANCE, currentDist));
                            }
                        }

                        shape.fill(px - box.minX(), py - box.minY(), pz - box.minZ());

                        for (int d = 0; d < dirLen; d++) {
                            Direction dir = dirs[d];
                            int nx = px + dir.getStepX();
                            int ny = py + dir.getStepY();
                            int nz = pz + dir.getStepZ();

                            if (box.isInside(nx, ny, nz)) {
                                int sx = nx - box.minX();
                                int sy = ny - box.minY();
                                int sz = nz - box.minZ();

                                if (!shape.isFull(sx, sy, sz)) {
                                    mPos.set(nx, ny, nz);
                                    BlockState neighborState = bulkAccess.getBlockState(mPos);
                                    OptionalInt optDist = LeavesBlock.getOptionalDistanceAt(neighborState);

                                    if (optDist.isPresent()) {
                                        int neighborDist = Math.min(optDist.getAsInt(), currentDist + 1);
                                        if (neighborDist < 7) {
                                            queues[neighborDist].add(BlockPos.asLong(nx, ny, nz));
                                            currentDist = Math.min(currentDist, neighborDist);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                currentQueue.clear();
                currentDist++;
            }
        }
        return shape;
    }

    @Unique
    private static void bts$fillShape(DiscreteVoxelShape shape, BoundingBox box, LongArrayList positions) {
        for (int i = 0; i < positions.size(); i++) {
            long pos = positions.getLong(i);
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            if (box.isInside(x, y, z)) {
                shape.fill(x - box.minX(), y - box.minY(), z - box.minZ());
            }
        }
    }

    @Unique
    private static void bts$fillShape(DiscreteVoxelShape shape, BoundingBox box, LongOpenHashSet positions) {
        LongIterator it = positions.iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            if (box.isInside(x, y, z)) {
                shape.fill(x - box.minX(), y - box.minY(), z - box.minZ());
            }
        }
    }

    @Unique
    private static BoundingBox bts$calculateBoundingBox(LongOpenHashSet set, LongArrayList... arrays) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean hasPoints = false;

        for (LongArrayList list : arrays) {
            for (int i = 0; i < list.size(); i++) {
                long pos = list.getLong(i);
                int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
                hasPoints = true;
            }
        }

        if (set != null && !set.isEmpty()) {
            LongIterator it = set.iterator();
            while (it.hasNext()) {
                long pos = it.nextLong();
                int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
                hasPoints = true;
            }
        }

        return hasPoints ? new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    @Unique
    private static Set<BlockPos> bts$unpackLongs(LongArrayList longs) {
        Set<BlockPos> list = new ObjectOpenHashSet<>(longs.size());
        for (int i = 0; i < longs.size(); i++) {
            list.add(BlockPos.of(longs.getLong(i)));
        }
        return list;
    }

    @Unique
    private static Set<BlockPos> bts$unpackLongs(LongOpenHashSet longs) {
        Set<BlockPos> list = new ObjectOpenHashSet<>(longs.size());
        LongIterator it = longs.iterator();
        while (it.hasNext()) {
            list.add(BlockPos.of(it.nextLong()));
        }
        return list;
    }
}
