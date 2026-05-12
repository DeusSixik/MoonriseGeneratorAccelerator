package dev.sixik.generator_accelerator.common.biome;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance spatial index for Minecraft climate parameter lookup.
 * Implements a thread-local, warm-started flat R-tree optimized for 7-dimensional
 * climate parameter queries (temperature, humidity, continentalness, erosion, depth,
 * weirdness, and an unused 7th dimension).
 *
 * <p>This specialized R-tree variant combines multiple optimization techniques to achieve
 * extreme query performance for nearest-neighbor searches in climate parameter space.
 *
 * <p><b>Key optimizations employed:</b>
 * <ol>
 *   <li><b>Flattened SoA memory layout</b> - Climate bounds stored in contiguous arrays
 *       ({@code bounds[]} and {@code structure[]}) for optimal cache locality during traversal.</li>
 *   <li><b>Thread-local context reuse</b> - Each thread maintains reusable search state
 *       ({@code SearchContext}) including stack and sorting buffers, eliminating allocation
 *       during queries.</li>
 *   <li><b>Warm-start heuristic</b> - Caches the last successful leaf node index to quickly
 *       test spatially adjacent queries, common in chunk generation.</li>
 *   <li><b>Branchless distance computation</b> - {@code bDist()} uses bitwise operations
 *       to compute squared distance to parameter intervals without conditional branches.</li>
 *   <li><b>Nearest-first traversal</b> - Child nodes are insertion-sorted by distance
 *       and visited in nearest-to-farthest order to maximize pruning effectiveness.</li>
 *   <li><b>Aggressive pruning</b> - Early rejection of nodes whose minimum possible distance
 *       exceeds the current best, enhanced by warm-start initialization.</li>
 * </ol>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li><i>Best-case (warm start hit):</i> O(1) - single distance calculation</li>
 *   <li><i>Average-case:</i> O(log n) with high constant-factor reduction vs vanilla R-tree</li>
 *   <li><i>Memory overhead:</i> ~112 bytes per node (7 params × 2 longs × 8 bytes)</li>
 *   <li><i>Thread safety:</i> Fully thread-safe for concurrent reads after construction</li>
 * </ul>
 *
 * @param <T> The value type associated with climate points (typically Biome)
 * @implNote Construct from {@link Climate.RTree} but stores data in flat arrays for
 *           faster traversal. Assumes infrequent construction with high query volume.
 * @see Climate.RTree Original Minecraft R-tree implementation
 * @version 3.0 Major revision focusing on cache-aware memory layout and branch reduction
 */
public class FlatClimateIndex<T> {
    private static final int PARAMS = 7;
    private static final int BYTES_PER_NODE = PARAMS * 2;

    // Read-only shared data
    private final long[] bounds;
    private final int[] structure; // [offset, count]
    private final int[] nodeOffsets;
    private final int[] nodeChildCounts;
    private final Object[] values;
    private final int rootIndex;
    private final int[] leafNodeIndices;
    private final int[] leafValueIndices;
    private final long[] offsetDistances;
    private final boolean hasOffsetDistances;
    private final boolean linearSearch;

    private static final int LINEAR_SEARCH_THRESHOLD =
            Integer.getInteger("ga.climate.linearSearchThreshold", 0);
    private static final int QUERY_CACHE_SIZE = queryCacheSize();
    private static final int QUERY_CACHE_MASK = QUERY_CACHE_SIZE - 1;

    /**
     * Thread-local search context to eliminate allocation overhead.
     * <p>Contains reusable buffers for DFS traversal stack (max 256 depth),
     * temporary arrays for child sorting, and warm-start tracking.
     * <p>Typical biome R-trees have depth < 10, so 256 provides ample headroom.
     */
    private static final class SearchContext {
        final int[] stack = new int[256]; // Depth is usually < 10, 256 is safe
        final long[] stackDistances = new long[256];
        final long[] childDistances = new long[6];
        final int[] childIndices = new int[6];
        final long[] cacheT = new long[QUERY_CACHE_SIZE];
        final long[] cacheH = new long[QUERY_CACHE_SIZE];
        final long[] cacheC = new long[QUERY_CACHE_SIZE];
        final long[] cacheE = new long[QUERY_CACHE_SIZE];
        final long[] cacheD = new long[QUERY_CACHE_SIZE];
        final long[] cacheW = new long[QUERY_CACHE_SIZE];
        final int[] cacheValuePlusOne = new int[QUERY_CACHE_SIZE];
        final int[] cacheLeafNode = new int[QUERY_CACHE_SIZE];
        int lastLeafNodeIndex = -1; // "Warm start" hint
        int lastValueIndex = -1;
        long lastT;
        long lastH;
        long lastC;
        long lastE;
        long lastD;
        long lastW;
    }

    private final ThreadLocal<SearchContext> ctx = ThreadLocal.withInitial(SearchContext::new);

    public FlatClimateIndex(List<Pair<Climate.ParameterPoint, T>> entries) {
        if (entries.isEmpty())
            throw new IllegalArgumentException("Empty list");

        final Climate.RTree<T> vanillaTree = Climate.RTree.create(entries);
        final int estimatedSize = entries.size() * 2;

        final TempStorage storage = new TempStorage(estimatedSize);
        this.rootIndex = flatten(vanillaTree.root, storage);

        this.bounds = Arrays.copyOf(storage.bounds, storage.cursor * BYTES_PER_NODE);
        this.structure = Arrays.copyOf(storage.structure, storage.cursor * 2);
        this.nodeOffsets = collectNodeOffsets(storage.cursor);
        this.nodeChildCounts = collectNodeChildCounts(storage.cursor);
        this.values = storage.values.toArray();
        this.offsetDistances = collectOffsetDistances(storage.cursor);
        this.hasOffsetDistances = hasNonZero(this.offsetDistances);
        this.leafNodeIndices = collectLeafNodeIndices(storage.cursor);
        this.leafValueIndices = collectLeafValueIndices(this.leafNodeIndices);
        this.linearSearch = this.leafNodeIndices.length <= LINEAR_SEARCH_THRESHOLD;
    }

    public FlatClimateIndex(Climate.RTree<T> vanillaTree) {
        final TempStorage storage = new TempStorage(1024);
        this.rootIndex = flatten(vanillaTree.root, storage);

        this.bounds = Arrays.copyOf(storage.bounds, storage.cursor * BYTES_PER_NODE);
        this.structure = Arrays.copyOf(storage.structure, storage.cursor * 2);
        this.nodeOffsets = collectNodeOffsets(storage.cursor);
        this.nodeChildCounts = collectNodeChildCounts(storage.cursor);
        this.values = storage.values.toArray();
        this.offsetDistances = collectOffsetDistances(storage.cursor);
        this.hasOffsetDistances = hasNonZero(this.offsetDistances);
        this.leafNodeIndices = collectLeafNodeIndices(storage.cursor);
        this.leafValueIndices = collectLeafValueIndices(this.leafNodeIndices);
        this.linearSearch = this.leafNodeIndices.length <= LINEAR_SEARCH_THRESHOLD;
    }

    public T search(final long[] array) {
        return search(array[0], array[1], array[2], array[3], array[4], array[5]);
    }

    public T search(
           final long t,
           final long h,
           final long c,
           final long e,
           final long d,
           final long w
    ) {
        final SearchContext s = ctx.get();
        if (s.lastValueIndex >= 0
                && s.lastT == t
                && s.lastH == h
                && s.lastC == c
                && s.lastE == e
                && s.lastD == d
                && s.lastW == w) {
            return (T) values[s.lastValueIndex];
        }

        int queryCacheSlot = -1;
        if (QUERY_CACHE_SIZE > 0) {
            queryCacheSlot = queryCacheSlot(t, h, c, e, d, w);
            int valuePlusOne = s.cacheValuePlusOne[queryCacheSlot];
            if (valuePlusOne != 0
                    && s.cacheT[queryCacheSlot] == t
                    && s.cacheH[queryCacheSlot] == h
                    && s.cacheC[queryCacheSlot] == c
                    && s.cacheE[queryCacheSlot] == e
                    && s.cacheD[queryCacheSlot] == d
                    && s.cacheW[queryCacheSlot] == w) {
                int valueIndex = valuePlusOne - 1;
                s.lastLeafNodeIndex = s.cacheLeafNode[queryCacheSlot];
                rememberLastQuery(s, valueIndex, t, h, c, e, d, w);
                return (T) values[valueIndex];
            }
        }

        if (linearSearch) {
            T result = searchLinear(s, t, h, c, e, d, w);
            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
            return result;
        }

        int bestLeafValueIndex = -1;
        long bestDist = Long.MAX_VALUE;

        /*
            If we've already searched for something, there's probably a new block nearby.
            We check the distance to the previous winner immediately.
         */
        if (s.lastLeafNodeIndex != -1) {
            bestDist = distance(s.lastLeafNodeIndex, t, h, c, e, d, w);

            /*
                the structure[node*2] for the sheet points to the index in the values array
             */
            bestLeafValueIndex = nodeOffsets[s.lastLeafNodeIndex];
            if (bestDist == 0L) {
                rememberLastQuery(s, bestLeafValueIndex, t, h, c, e, d, w);
                storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                return (T) values[bestLeafValueIndex];
            }
        }

        final int[] stack = s.stack;
        final long[] stackDistances = s.stackDistances;
        int sp = 0;
        long rootDistance = distance(rootIndex, t, h, c, e, d, w);
        if (rootDistance >= bestDist && bestLeafValueIndex >= 0) {
            rememberLastQuery(s, bestLeafValueIndex, t, h, c, e, d, w);
            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
            return (T) values[bestLeafValueIndex];
        }
        stack[sp] = rootIndex;
        stackDistances[sp++] = rootDistance;

        final long[] childDists = s.childDistances;
        final int[] childIdxs = s.childIndices;

        while (sp > 0) {
            --sp;
            int nodeIdx = stack[sp];
            long nodeDistance = stackDistances[sp];

            /*
                Aggressive Pruning: if the node itself is further away than the best one found, skip it.
                This is especially effective thanks to Warm Start.
                For a leaf, we will check the distance within the treatment.
             */
            if (nodeDistance >= bestDist) {
                continue;
            }

            final int offset = nodeOffsets[nodeIdx];
            final int childCount = nodeChildCounts[nodeIdx];

            if (childCount == 0) {
                if (nodeDistance < bestDist) {
                    bestDist = nodeDistance;
                    bestLeafValueIndex = offset;
                    s.lastLeafNodeIndex = nodeIdx; // Запоминаем для следующего раза
                }
            } else {
                int validChildren = 0;

                /*
                    Linear memory access
                 */
                for (int i = 0; i < childCount; i++) {
                    final int childNodeIdx = offset + i;
                    final long dist = distance(childNodeIdx, t, h, c, e, d, w);

                    /*
                        If the child is already worse than the current best, don't even add it to the sorting
                     */
                    if (dist < bestDist) {
                        childDists[validChildren] = dist;
                        childIdxs[validChildren] = childNodeIdx;
                        validChildren++;
                    }
                }

                if (validChildren == 0) continue;

                /*
                    "Nearest Last" sorting (Insertion Sort).
                    The nearest child must be at the end of the array to reach the TOP of the stack.
                 */
                for (int i = 1; i < validChildren; i++) {
                    final long cd = childDists[i];
                    final int ci = childIdxs[i];
                    int j = i - 1;
                    while (j >= 0 && childDists[j] < cd) {
                        childDists[j + 1] = childDists[j];
                        childIdxs[j + 1] = childIdxs[j];
                        j--;
                    }
                    childDists[j + 1] = cd;
                    childIdxs[j + 1] = ci;
                }

                /*
                    Push to stack
                 */
                for (int i = 0; i < validChildren; i++) {
                    stack[sp] = childIdxs[i];
                    stackDistances[sp++] = childDists[i];
                }
            }
        }

        rememberLastQuery(s, bestLeafValueIndex, t, h, c, e, d, w);
        storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
        return (T) values[bestLeafValueIndex];
    }

    private T searchLinear(
            SearchContext s,
            long t,
            long h,
            long c,
            long e,
            long d,
            long w
    ) {
        int bestLeafNodeIndex = s.lastLeafNodeIndex;
        int bestLeafValueIndex = -1;
        long bestDist = Long.MAX_VALUE;
        if (bestLeafNodeIndex != -1) {
            bestDist = distance(bestLeafNodeIndex, t, h, c, e, d, w);
            bestLeafValueIndex = nodeOffsets[bestLeafNodeIndex];
            if (bestDist == 0L) {
                rememberLastQuery(s, bestLeafValueIndex, t, h, c, e, d, w);
                return (T) values[bestLeafValueIndex];
            }
        }

        int[] leaves = this.leafNodeIndices;
        int[] leafValues = this.leafValueIndices;
        for (int i = 0; i < leaves.length; i++) {
            int leaf = leaves[i];
            if (leaf == bestLeafNodeIndex) {
                continue;
            }
            long dist = distance(leaf, t, h, c, e, d, w);
            if (dist < bestDist) {
                bestDist = dist;
                bestLeafNodeIndex = leaf;
                bestLeafValueIndex = leafValues[i];
            }
        }

        s.lastLeafNodeIndex = bestLeafNodeIndex;
        rememberLastQuery(s, bestLeafValueIndex, t, h, c, e, d, w);
        return (T) values[bestLeafValueIndex];
    }

    private static void rememberLastQuery(
            SearchContext s,
            int valueIndex,
            long t,
            long h,
            long c,
            long e,
            long d,
            long w
    ) {
        s.lastValueIndex = valueIndex;
        s.lastT = t;
        s.lastH = h;
        s.lastC = c;
        s.lastE = e;
        s.lastD = d;
        s.lastW = w;
    }

    private static int queryCacheSize() {
        int configured = Integer.getInteger("ga.climate.queryCacheSize", 0);
        if (configured <= 0) {
            return 0;
        }
        return Integer.highestOneBit(configured);
    }

    private static int queryCacheSlot(long t, long h, long c, long e, long d, long w) {
        long mixed = t * 0x9E3779B97F4A7C15L;
        mixed ^= Long.rotateLeft(h, 11);
        mixed ^= Long.rotateLeft(c, 23);
        mixed ^= Long.rotateLeft(e, 37);
        mixed ^= Long.rotateLeft(d, 43);
        mixed ^= Long.rotateLeft(w, 53);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) mixed & QUERY_CACHE_MASK;
    }

    private static void storeQueryCache(
            SearchContext s,
            int slot,
            long t,
            long h,
            long c,
            long e,
            long d,
            long w
    ) {
        if (slot < 0 || s.lastValueIndex < 0 || s.lastLeafNodeIndex < 0) {
            return;
        }
        s.cacheT[slot] = t;
        s.cacheH[slot] = h;
        s.cacheC[slot] = c;
        s.cacheE[slot] = e;
        s.cacheD[slot] = d;
        s.cacheW[slot] = w;
        s.cacheLeafNode[slot] = s.lastLeafNodeIndex;
        s.cacheValuePlusOne[slot] = s.lastValueIndex + 1;
    }

    /**
     * Computes squared distance from value to [min, max] interval without branches.
     * <p>Uses bitwise operations: {@code (x & ~(x >> 63))} is equivalent to {@code max(0, x)}.
     * This avoids branch mispredictions in the hot distance calculation path.
     *
     * @return (max(0, val - max) + max(0, min - val))²
     */
    private long distance(int nodeIdx, long t, long h, long c, long e, long d, long w) {
        int base = nodeIdx * BYTES_PER_NODE;
        long dist = 0;
        dist += bDist(bounds[base], bounds[base + 1], t);
        dist += bDist(bounds[base + 2], bounds[base + 3], h);
        dist += bDist(bounds[base + 4], bounds[base + 5], c);
        dist += bDist(bounds[base + 6], bounds[base + 7], e);
        dist += bDist(bounds[base + 8], bounds[base + 9], d);
        dist += bDist(bounds[base + 10], bounds[base + 11], w);
        if (hasOffsetDistances) {
            dist += offsetDistances[nodeIdx];
        }
        return dist;
    }

    /**
     * Square of distance to interval. <br>
     * logic: max(0, val - max) + max(0, min - val) using bit hacks
     */
    private static long bDist(long min, long max, long val) {
        long d1 = val - max;
        long d2 = min - val;
        long d = (d1 & ~(d1 >> 63)) + (d2 & ~(d2 >> 63));
        return d * d;
    }

    private int[] collectLeafNodeIndices(int nodeCount) {
        int count = 0;
        for (int i = 0; i < nodeCount; i++) {
            if (nodeChildCounts[i] == 0) {
                count++;
            }
        }
        int[] leaves = new int[count];
        int cursor = 0;
        for (int i = 0; i < nodeCount; i++) {
            if (nodeChildCounts[i] == 0) {
                leaves[cursor++] = i;
            }
        }
        return leaves;
    }

    private int[] collectNodeOffsets(int nodeCount) {
        int[] offsets = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            offsets[i] = structure[i * 2];
        }
        return offsets;
    }

    private int[] collectNodeChildCounts(int nodeCount) {
        int[] counts = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            counts[i] = structure[i * 2 + 1];
        }
        return counts;
    }

    private long[] collectOffsetDistances(int nodeCount) {
        long[] distances = new long[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            int base = i * BYTES_PER_NODE;
            distances[i] = bDist(bounds[base + 12], bounds[base + 13], 0);
        }
        return distances;
    }

    private int[] collectLeafValueIndices(int[] leaves) {
        int[] valueIndices = new int[leaves.length];
        for (int i = 0; i < leaves.length; i++) {
            valueIndices[i] = nodeOffsets[leaves[i]];
        }
        return valueIndices;
    }

    private static boolean hasNonZero(long[] values) {
        for (long value : values) {
            if (value != 0L) {
                return true;
            }
        }
        return false;
    }

    private int flatten(Climate.RTree.Node<T> node, TempStorage storage) {
        final int index = storage.allocate();

        final Climate.Parameter[] params = node.parameterSpace;

        final int base = index * BYTES_PER_NODE;
        for (int i = 0; i < PARAMS; i++) {
            storage.bounds[base + i * 2] = params[i].min();
            storage.bounds[base + i * 2 + 1] = params[i].max();
        }

        if (node instanceof Climate.RTree.Leaf) {
            storage.structure[index * 2 + 1] = 0; // leaf flag
            storage.values.add(((Climate.RTree.Leaf<T>)node).value);
            storage.structure[index * 2] = storage.values.size() - 1;
        } else {
            final Climate.RTree.SubTree<T> sub = (Climate.RTree.SubTree<T>) node;
            final Climate.RTree.Node<T>[] children = sub.children;

            storage.structure[index * 2 + 1] = children.length;

            final int childrenStart = storage.cursor;
            storage.cursor += children.length;
            storage.checkResize();

            storage.structure[index * 2] = childrenStart;

            for (int i = 0; i < children.length; i++) {
                flattenAt(children[i], storage, childrenStart + i);
            }
        }
        return index;
    }

    private void flattenAt(Climate.RTree.Node<T> node, TempStorage storage, int index) {
        final Climate.Parameter[] params = node.parameterSpace;
        final int base = index * BYTES_PER_NODE;
        for (int i = 0; i < PARAMS; i++) {
            storage.bounds[base + i * 2] = params[i].min();
            storage.bounds[base + i * 2 + 1] = params[i].max();
        }

        if (node instanceof Climate.RTree.Leaf) {
            storage.structure[index * 2 + 1] = 0;
            storage.values.add(((Climate.RTree.Leaf<T>)node).value);
            storage.structure[index * 2] = storage.values.size() - 1;
        } else {
            final Climate.RTree.SubTree<T> sub = (Climate.RTree.SubTree<T>) node;
            final Climate.RTree.Node<T>[] children = sub.children;

            storage.structure[index * 2 + 1] = children.length;
            final int childrenStart = storage.cursor;
            storage.cursor += children.length;
            storage.checkResize();

            storage.structure[index * 2] = childrenStart;
            for (int i = 0; i < children.length; i++) {
                flattenAt(children[i], storage, childrenStart + i);
            }
        }
    }

    private static class TempStorage {
        long[] bounds;
        int[] structure;
        final List<Object> values = new ArrayList<>();
        int cursor = 0;

        TempStorage(int size) {
            this.bounds = new long[size * BYTES_PER_NODE];
            this.structure = new int[size * 2];
        }

        int allocate() {
            int i = cursor++;
            checkResize();
            return i;
        }

        void checkResize() {
            if (cursor * 2 >= structure.length) {
                bounds = Arrays.copyOf(bounds, bounds.length * 2);
                structure = Arrays.copyOf(structure, structure.length * 2);
            }
        }
    }
}
