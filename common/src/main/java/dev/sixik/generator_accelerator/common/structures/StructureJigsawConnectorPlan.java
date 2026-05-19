package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * Cached per-rotation jigsaw connector data. Runtime calls still consume the
 * same RNG sequence as vanilla shuffle, then apply selection_priority by a
 * stable bucket pass instead of sorting StructureBlockInfo objects each time.
 */
public final class StructureJigsawConnectorPlan {
    private static final String SELECTION_PRIORITY_TAG = "selection_priority";
    private static final ThreadLocal<int[]> ORDER = ThreadLocal.withInitial(() -> new int[16]);
    private static final ThreadLocal<int[]> GROUPED_ORDER = ThreadLocal.withInitial(() -> new int[16]);
    private static final ThreadLocal<int[]> BUCKET_CURSORS = ThreadLocal.withInitial(() -> new int[4]);

    private final StructureTemplate.StructureBlockInfo[] blocks;
    private final int[] priorityRanks;
    private final int[] bucketStarts;

    private StructureJigsawConnectorPlan(
            StructureTemplate.StructureBlockInfo[] blocks,
            int[] priorityRanks,
            int[] bucketStarts
    ) {
        this.blocks = blocks;
        this.priorityRanks = priorityRanks;
        this.bucketStarts = bucketStarts;
    }

    public static StructureJigsawConnectorPlan compile(List<StructureTemplate.StructureBlockInfo> blocks) {
        int size = blocks.size();
        StructureTemplate.StructureBlockInfo[] blockArray = new StructureTemplate.StructureBlockInfo[size];
        int[] priorityArray = new int[size];

        for (int i = 0; i < size; i++) {
            StructureTemplate.StructureBlockInfo blockInfo = blocks.get(i);
            blockArray[i] = blockInfo;
            priorityArray[i] = selectionPriority(blockInfo);
        }

        int[] uniquePriorities = uniquePrioritiesDescending(priorityArray);
        if (uniquePriorities.length <= 1) {
            return new StructureJigsawConnectorPlan(blockArray, new int[0], new int[0]);
        }

        int[] ranks = new int[size];
        int[] bucketSizes = new int[uniquePriorities.length];
        for (int i = 0; i < size; i++) {
            int rank = priorityRank(uniquePriorities, priorityArray[i]);
            ranks[i] = rank;
            bucketSizes[rank]++;
        }

        int[] bucketStarts = new int[uniquePriorities.length];
        int cursor = 0;
        for (int i = 0; i < bucketSizes.length; i++) {
            bucketStarts[i] = cursor;
            cursor += bucketSizes[i];
        }

        return new StructureJigsawConnectorPlan(blockArray, ranks, bucketStarts);
    }

    public ObjectArrayList<StructureTemplate.StructureBlockInfo> shuffled(BlockPos offset, RandomSource random) {
        int count = this.blocks.length;
        ObjectArrayList<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(count);
        if (count == 0) {
            return result;
        }

        boolean noOffset = offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0;
        if (this.bucketStarts.length == 0) {
            return shuffledUniform(offset, random, count, noOffset);
        }

        int[] order = order(count);
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        shuffleOrder(order, count, random);

        int[] groupedOrder = groupedOrder(count);
        int[] cursors = bucketCursors(this.bucketStarts.length);
        System.arraycopy(this.bucketStarts, 0, cursors, 0, this.bucketStarts.length);

        for (int i = 0; i < count; i++) {
            int blockIndex = order[i];
            groupedOrder[cursors[this.priorityRanks[blockIndex]]++] = blockIndex;
        }
        appendInOrder(result, groupedOrder, count, noOffset, offset);
        return result;
    }

    private ObjectArrayList<StructureTemplate.StructureBlockInfo> shuffledUniform(
            BlockPos offset,
            RandomSource random,
            int count,
            boolean noOffset
    ) {
        ObjectArrayList<StructureTemplate.StructureBlockInfo> result = new ObjectArrayList<>(count);
        for (int i = 0; i < count; i++) {
            append(result, this.blocks[i], noOffset, offset);
        }
        Util.shuffle(result, random);
        return result;
    }

    private void appendInOrder(
            ObjectArrayList<StructureTemplate.StructureBlockInfo> result,
            int[] order,
            int count,
            boolean noOffset,
            BlockPos offset
    ) {
        for (int i = 0; i < count; i++) {
            append(result, this.blocks[order[i]], noOffset, offset);
        }
    }

    private static void append(
            ObjectArrayList<StructureTemplate.StructureBlockInfo> result,
            StructureTemplate.StructureBlockInfo blockInfo,
            boolean noOffset,
            BlockPos offset
    ) {
        if (noOffset) {
            result.add(blockInfo);
            return;
        }
        result.add(new StructureTemplate.StructureBlockInfo(
                blockInfo.pos().offset(offset),
                blockInfo.state(),
                blockInfo.nbt()
        ));
    }

    private static int selectionPriority(StructureTemplate.StructureBlockInfo blockInfo) {
        CompoundTag tag = blockInfo.nbt();
        return tag == null ? 0 : tag.getInt(SELECTION_PRIORITY_TAG);
    }

    private static int[] uniquePrioritiesDescending(int[] priorities) {
        int count = priorities.length;
        if (count <= 1) {
            return new int[0];
        }

        int[] sorted = priorities.clone();
        Arrays.sort(sorted);
        int[] unique = new int[count];
        int uniqueCount = 0;
        int lastPriority = 0;
        for (int i = count - 1; i >= 0; i--) {
            int priority = sorted[i];
            if (uniqueCount == 0 || priority != lastPriority) {
                unique[uniqueCount++] = priority;
                lastPriority = priority;
            }
        }
        return uniqueCount <= 1 ? new int[0] : Arrays.copyOf(unique, uniqueCount);
    }

    private static int priorityRank(int[] priorities, int priority) {
        for (int i = 0; i < priorities.length; i++) {
            if (priorities[i] == priority) {
                return i;
            }
        }
        return priorities.length - 1;
    }

    private static int[] order(int requiredSize) {
        int[] order = ORDER.get();
        if (order.length < requiredSize) {
            order = new int[Math.max(requiredSize, order.length << 1)];
            ORDER.set(order);
        }
        return order;
    }

    private static int[] groupedOrder(int requiredSize) {
        int[] order = GROUPED_ORDER.get();
        if (order.length < requiredSize) {
            order = new int[Math.max(requiredSize, order.length << 1)];
            GROUPED_ORDER.set(order);
        }
        return order;
    }

    private static int[] bucketCursors(int requiredSize) {
        int[] cursors = BUCKET_CURSORS.get();
        if (cursors.length < requiredSize) {
            cursors = new int[Math.max(requiredSize, cursors.length << 1)];
            BUCKET_CURSORS.set(cursors);
        }
        return cursors;
    }

    private static void shuffleOrder(int[] order, int count, RandomSource random) {
        for (int remaining = count; remaining > 1; remaining--) {
            int picked = random.nextInt(remaining);
            int last = remaining - 1;
            int previousLast = order[last];
            order[last] = order[picked];
            order[picked] = previousLast;
        }
    }
}
