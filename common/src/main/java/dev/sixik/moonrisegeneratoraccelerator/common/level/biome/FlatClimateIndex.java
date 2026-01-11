package dev.sixik.moonrisegeneratoraccelerator.common.level.biome;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

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
    private final Object[] values;
    private final int rootIndex;

    /**
     * Thread-local search context to eliminate allocation overhead.
     * <p>Contains reusable buffers for DFS traversal stack (max 256 depth),
     * temporary arrays for child sorting, and warm-start tracking.
     * <p>Typical biome R-trees have depth < 10, so 256 provides ample headroom.
     */
    private static final class SearchContext {
        final int[] stack = new int[256]; // Depth is usually < 10, 256 is safe
        final long[] childDistances = new long[6];
        final int[] childIndices = new int[6];
        int lastLeafNodeIndex = -1; // "Warm start" hint
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
        this.values = storage.values.toArray();
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
            bestLeafValueIndex = structure[s.lastLeafNodeIndex * 2];
        }

        final int[] stack = s.stack;
        int sp = 0;
        stack[sp++] = rootIndex;

        final long[] childDists = s.childDistances;
        final int[] childIdxs = s.childIndices;

        while (sp > 0) {
            int nodeIdx = stack[--sp];

            /*
                Aggressive Pruning: if the node itself is further away than the best one found, skip it.
                This is especially effective thanks to Warm Start.
                For a leaf, we will check the distance within the treatment.
             */
            if (nodeIdx == rootIndex) {
                if (distance(rootIndex, t, h, c, e, d, w) >= bestDist) continue;
            }

            final int structIdx = nodeIdx * 2;
            final int offset = structure[structIdx];
            final int childCount = structure[structIdx + 1];

            if (childCount == 0) {
                final long dist = distance(nodeIdx, t, h, c, e, d, w);
                if (dist < bestDist) {
                    bestDist = dist;
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
                    stack[sp++] = childIdxs[i];
                }
            }
        }

        return (T) values[bestLeafValueIndex];
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
        dist += bDist(bounds[base + 12], bounds[base + 13], 0);
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
