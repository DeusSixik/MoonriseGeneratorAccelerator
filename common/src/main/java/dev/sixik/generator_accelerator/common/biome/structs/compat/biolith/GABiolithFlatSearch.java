package dev.sixik.generator_accelerator.common.biome.structs.compat.biolith;

import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;

import java.util.Arrays;
import java.util.Optional;

/**
 * Flat two-nearest search for Biolith's RTree hook.
 *
 * <p>Biolith asks the vanilla tree for the nearest and second-nearest biome
 * nodes on every biome lookup. Traversing the object tree and invoking the
 * distance metric for every visited node shows up in heavy TerraBlender/Biolith
 * packs, so this mirrors the tree into compact arrays and keeps hot search state
 * in a thread-local scratch.
 */
public final class GABiolithFlatSearch<T> {
    private static final int PARAMS = 7;
    private static final int BOUNDS_PER_NODE = PARAMS * 2;

    private final long[] bounds;
    private final int[] nodeOffsets;
    private final int[] nodeChildCounts;
    private final Object[] leafNodes;
    private final long[] offsetDistances;
    private final boolean hasOffsetDistances;
    private final int rootIndex;
    private final ThreadLocal<SearchContext> context = ThreadLocal.withInitial(SearchContext::new);

    public GABiolithFlatSearch(Climate.RTree<T> tree) {
        Builder<T> builder = new Builder<>(256);
        this.rootIndex = tree.root == null ? -1 : flatten(tree.root, builder);
        int nodeCount = builder.cursor;
        this.bounds = Arrays.copyOf(builder.bounds, nodeCount * BOUNDS_PER_NODE);
        this.nodeOffsets = Arrays.copyOf(builder.nodeOffsets, nodeCount);
        this.nodeChildCounts = Arrays.copyOf(builder.nodeChildCounts, nodeCount);
        this.leafNodes = Arrays.copyOf(builder.leafNodes, nodeCount);
        this.offsetDistances = collectOffsetDistances(this.bounds, nodeCount);
        this.hasOffsetDistances = hasNonZero(this.offsetDistances);
    }

    @SuppressWarnings("unchecked")
    public BiolithFittestNodes<T> search(Climate.TargetPoint target) {
        if (this.rootIndex < 0) {
            return new BiolithFittestNodes<>(null, Long.MAX_VALUE);
        }

        SearchContext scratch = this.context.get();
        long t = target.temperature();
        long h = target.humidity();
        long c = target.continentalness();
        long e = target.erosion();
        long d = target.depth();
        long w = target.weirdness();

        int bestNode = scratch.previousUltimateNode;
        int secondNode = scratch.previousPenultimateNode;
        Climate.RTree.Leaf<T> best = leafAt(bestNode);
        Climate.RTree.Leaf<T> second = leafAt(secondNode);
        long bestDistance = best == null ? Long.MAX_VALUE : distance(bestNode, t, h, c, e, d, w);
        long secondDistance = second == null ? Long.MAX_VALUE : distance(secondNode, t, h, c, e, d, w);
        if (bestDistance > secondDistance) {
            Climate.RTree.Leaf<T> leafSwap = best;
            best = second;
            second = leafSwap;
            int nodeSwap = bestNode;
            bestNode = secondNode;
            secondNode = nodeSwap;
            long distanceSwap = bestDistance;
            bestDistance = secondDistance;
            secondDistance = distanceSwap;
        }
        if (best != null && second != null && sameBiomeKey(best, second)) {
            second = null;
            secondNode = -1;
            secondDistance = Long.MAX_VALUE;
        }

        int[] stack = scratch.stack;
        long[] stackDistances = scratch.stackDistances;
        int sp = 0;
        long rootDistance = distanceCapped(this.rootIndex, t, h, c, e, d, w, secondDistance);
        if (rootDistance < secondDistance) {
            stack[sp] = this.rootIndex;
            stackDistances[sp++] = rootDistance;
        }

        long[] childDistances = scratch.childDistances;
        int[] childIndices = scratch.childIndices;
        while (sp > 0) {
            --sp;
            int node = stack[sp];
            long nodeDistance = stackDistances[sp];
            if (nodeDistance >= secondDistance) {
                continue;
            }

            int childCount = this.nodeChildCounts[node];
            if (childCount == 0) {
                Climate.RTree.Leaf<T> leaf = (Climate.RTree.Leaf<T>) this.leafNodes[node];
                if (leaf == null) {
                    continue;
                }
                if (best == null || nodeDistance < bestDistance) {
                    if (best != null && !sameBiomeKey(leaf, best)) {
                        second = best;
                        secondNode = bestNode;
                        secondDistance = bestDistance;
                    }
                    best = leaf;
                    bestNode = node;
                    bestDistance = nodeDistance;
                } else if (!sameBiomeKey(leaf, best)) {
                    second = leaf;
                    secondNode = node;
                    secondDistance = nodeDistance;
                }
                continue;
            }

            if (childCount > childIndices.length) {
                childIndices = scratch.growChildren(childCount);
                childDistances = scratch.childDistances;
            }
            int childStart = this.nodeOffsets[node];
            int validChildren = 0;
            for (int i = 0; i < childCount; i++) {
                int child = childStart + i;
                long childDistance = distanceCapped(child, t, h, c, e, d, w, secondDistance);
                if (childDistance < secondDistance) {
                    childDistances[validChildren] = childDistance;
                    childIndices[validChildren] = child;
                    validChildren++;
                }
            }
            if (validChildren == 0) {
                continue;
            }
            if (sp + validChildren > stack.length) {
                stack = scratch.growStack(sp + validChildren);
                stackDistances = scratch.stackDistances;
            }
            if (validChildren == 1) {
                stack[sp] = childIndices[0];
                stackDistances[sp++] = childDistances[0];
                continue;
            }
            sortChildrenFarToNear(childDistances, childIndices, validChildren);
            for (int i = 0; i < validChildren; i++) {
                stack[sp] = childIndices[i];
                stackDistances[sp++] = childDistances[i];
            }
        }

        scratch.previousUltimateNode = bestNode;
        scratch.previousPenultimateNode = secondNode;
        return scratch.result(best, bestDistance, second, secondDistance);
    }

    @SuppressWarnings("unchecked")
    private Climate.RTree.Leaf<T> leafAt(int node) {
        return node >= 0 && node < this.leafNodes.length
                ? (Climate.RTree.Leaf<T>) this.leafNodes[node]
                : null;
    }

    private long distance(int node, long t, long h, long c, long e, long d, long w) {
        int base = node * BOUNDS_PER_NODE;
        long distance = 0L;
        distance += bDist(this.bounds[base], this.bounds[base + 1], t);
        distance += bDist(this.bounds[base + 2], this.bounds[base + 3], h);
        distance += bDist(this.bounds[base + 4], this.bounds[base + 5], c);
        distance += bDist(this.bounds[base + 6], this.bounds[base + 7], e);
        distance += bDist(this.bounds[base + 8], this.bounds[base + 9], d);
        distance += bDist(this.bounds[base + 10], this.bounds[base + 11], w);
        if (this.hasOffsetDistances) {
            distance += this.offsetDistances[node];
        }
        return distance;
    }

    private long distanceCapped(int node, long t, long h, long c, long e, long d, long w, long cap) {
        int base = node * BOUNDS_PER_NODE;
        long distance = 0L;
        distance += bDist(this.bounds[base], this.bounds[base + 1], t);
        if (distance >= cap) return cap;
        distance += bDist(this.bounds[base + 2], this.bounds[base + 3], h);
        if (distance >= cap) return cap;
        distance += bDist(this.bounds[base + 4], this.bounds[base + 5], c);
        if (distance >= cap) return cap;
        distance += bDist(this.bounds[base + 6], this.bounds[base + 7], e);
        if (distance >= cap) return cap;
        distance += bDist(this.bounds[base + 8], this.bounds[base + 9], d);
        if (distance >= cap) return cap;
        distance += bDist(this.bounds[base + 10], this.bounds[base + 11], w);
        if (this.hasOffsetDistances) {
            distance += this.offsetDistances[node];
        }
        return distance >= cap ? cap : distance;
    }

    private static long[] collectOffsetDistances(long[] bounds, int nodeCount) {
        long[] distances = new long[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            int base = i * BOUNDS_PER_NODE;
            distances[i] = bDist(bounds[base + 12], bounds[base + 13], 0L);
        }
        return distances;
    }

    private static boolean hasNonZero(long[] values) {
        for (long value : values) {
            if (value != 0L) {
                return true;
            }
        }
        return false;
    }

    private static long bDist(long min, long max, long value) {
        long over = value - max;
        long under = min - value;
        long delta = (over & ~(over >> 63)) + (under & ~(under >> 63));
        return delta * delta;
    }

    private static void sortChildrenFarToNear(long[] distances, int[] indices, int size) {
        for (int i = 1; i < size; i++) {
            long distance = distances[i];
            int index = indices[i];
            int j = i - 1;
            while (j >= 0 && distances[j] < distance) {
                distances[j + 1] = distances[j];
                indices[j + 1] = indices[j];
                j--;
            }
            distances[j + 1] = distance;
            indices[j + 1] = index;
        }
    }

    private static boolean sameBiomeKey(Climate.RTree.Leaf<?> left, Climate.RTree.Leaf<?> right) {
        Object leftValue = left.value;
        Object rightValue = right.value;
        if (leftValue == rightValue) {
            return true;
        }
        if (leftValue instanceof Holder.Reference<?> leftHolder && rightValue instanceof Holder.Reference<?> rightHolder) {
            return leftHolder.key().equals(rightHolder.key());
        }
        if (leftValue instanceof Holder<?> leftHolder && rightValue instanceof Holder<?> rightHolder) {
            Optional<? extends ResourceKey<?>> leftKey = leftHolder.unwrapKey();
            Optional<? extends ResourceKey<?>> rightKey = rightHolder.unwrapKey();
            if (leftKey.isPresent() || rightKey.isPresent()) {
                return leftKey.equals(rightKey);
            }
            Object leftDirectValue = leftHolder.value();
            Object rightDirectValue = rightHolder.value();
            return leftDirectValue == rightDirectValue
                    || (leftDirectValue != null && leftDirectValue.equals(rightDirectValue));
        }
        return leftValue != null && leftValue.equals(rightValue);
    }

    private static <T> int flatten(Climate.RTree.Node<T> node, Builder<T> builder) {
        int index = builder.allocate();
        writeBounds(node, builder, index);
        if (node instanceof Climate.RTree.Leaf<T> leaf) {
            builder.leafNodes[index] = leaf;
            return index;
        }

        Climate.RTree.SubTree<T> subtree = (Climate.RTree.SubTree<T>) node;
        Climate.RTree.Node<T>[] children = subtree.children;
        int childStart = builder.reserve(children.length);
        builder.nodeOffsets[index] = childStart;
        builder.nodeChildCounts[index] = children.length;
        for (int i = 0; i < children.length; i++) {
            flattenAt(children[i], builder, childStart + i);
        }
        return index;
    }

    private static <T> void flattenAt(Climate.RTree.Node<T> node, Builder<T> builder, int index) {
        builder.ensure(index + 1);
        writeBounds(node, builder, index);
        if (node instanceof Climate.RTree.Leaf<T> leaf) {
            builder.leafNodes[index] = leaf;
            return;
        }

        Climate.RTree.SubTree<T> subtree = (Climate.RTree.SubTree<T>) node;
        Climate.RTree.Node<T>[] children = subtree.children;
        int childStart = builder.reserve(children.length);
        builder.nodeOffsets[index] = childStart;
        builder.nodeChildCounts[index] = children.length;
        for (int i = 0; i < children.length; i++) {
            flattenAt(children[i], builder, childStart + i);
        }
    }

    private static <T> void writeBounds(Climate.RTree.Node<T> node, Builder<T> builder, int index) {
        Climate.Parameter[] parameters = node.parameterSpace;
        int base = index * BOUNDS_PER_NODE;
        builder.ensure(index + 1);
        for (int i = 0; i < PARAMS; i++) {
            builder.bounds[base + i * 2] = parameters[i].min();
            builder.bounds[base + i * 2 + 1] = parameters[i].max();
        }
    }

    private static final class SearchContext {
        int[] stack = new int[256];
        long[] stackDistances = new long[256];
        int[] childIndices = new int[8];
        long[] childDistances = new long[8];
        int previousUltimateNode = -1;
        int previousPenultimateNode = -1;
        Climate.RTree.Leaf<?> lastBest;
        Climate.RTree.Leaf<?> lastSecond;
        long lastBestDistance;
        long lastSecondDistance;
        BiolithFittestNodes<?> lastResult;

        int[] growStack(int required) {
            int newLength = this.stack.length;
            while (newLength < required) {
                newLength <<= 1;
            }
            this.stack = Arrays.copyOf(this.stack, newLength);
            this.stackDistances = Arrays.copyOf(this.stackDistances, newLength);
            return this.stack;
        }

        int[] growChildren(int required) {
            int newLength = this.childIndices.length;
            while (newLength < required) {
                newLength <<= 1;
            }
            this.childIndices = Arrays.copyOf(this.childIndices, newLength);
            this.childDistances = Arrays.copyOf(this.childDistances, newLength);
            return this.childIndices;
        }

        @SuppressWarnings("unchecked")
        <T> BiolithFittestNodes<T> result(
                Climate.RTree.Leaf<T> best,
                long bestDistance,
                Climate.RTree.Leaf<T> second,
                long secondDistance
        ) {
            BiolithFittestNodes<?> cached = this.lastResult;
            if (cached != null
                    && this.lastBest == best
                    && this.lastSecond == second
                    && this.lastBestDistance == bestDistance
                    && this.lastSecondDistance == secondDistance) {
                return (BiolithFittestNodes<T>) cached;
            }

            BiolithFittestNodes<T> result = second == null
                    ? new BiolithFittestNodes<>(best, bestDistance)
                    : new BiolithFittestNodes<>(best, bestDistance, second, secondDistance);
            this.lastBest = best;
            this.lastSecond = second;
            this.lastBestDistance = bestDistance;
            this.lastSecondDistance = secondDistance;
            this.lastResult = result;
            return result;
        }
    }

    private static final class Builder<T> {
        long[] bounds;
        int[] nodeOffsets;
        int[] nodeChildCounts;
        Object[] leafNodes;
        int cursor;

        Builder(int capacity) {
            this.bounds = new long[capacity * BOUNDS_PER_NODE];
            this.nodeOffsets = new int[capacity];
            this.nodeChildCounts = new int[capacity];
            this.leafNodes = new Object[capacity];
        }

        int allocate() {
            int index = this.cursor++;
            ensure(this.cursor);
            return index;
        }

        int reserve(int count) {
            int start = this.cursor;
            this.cursor += count;
            ensure(this.cursor);
            return start;
        }

        void ensure(int requiredNodes) {
            if (requiredNodes <= this.nodeOffsets.length) {
                return;
            }
            int newLength = this.nodeOffsets.length;
            while (newLength < requiredNodes) {
                newLength <<= 1;
            }
            this.bounds = Arrays.copyOf(this.bounds, newLength * BOUNDS_PER_NODE);
            this.nodeOffsets = Arrays.copyOf(this.nodeOffsets, newLength);
            this.nodeChildCounts = Arrays.copyOf(this.nodeChildCounts, newLength);
            this.leafNodes = Arrays.copyOf(this.leafNodes, newLength);
        }
    }
}
