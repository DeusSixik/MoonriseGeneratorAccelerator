package dev.sixik.generator_accelerator.common.biome.climate;

import com.mojang.datafixers.util.Pair;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

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
    private static final int BOUNDS_STRIDE_WITH_OFFSET = PARAMS * 2;
    private static final int QUERY_DIMS = 6;
    private static final int BOUNDS_STRIDE_NO_OFFSET = QUERY_DIMS * 2;
    private static final int QUERY_DIMENSION_MASK = 0x3F;
    private static final LongAdder INDEX_BUILDS = new LongAdder();
    private static final LongAdder SEARCHES = new LongAdder();
    private static final LongAdder LAST_VALUE_CACHE_HITS = new LongAdder();
    private static final LongAdder QUERY_CACHE_PROBES = new LongAdder();
    private static final LongAdder QUERY_CACHE_HITS = new LongAdder();
    private static final LongAdder QUERY_CACHE_DISABLES = new LongAdder();
    private static final LongAdder LINEAR_SEARCH_CALLS = new LongAdder();
    private static final LongAdder TREE_SEARCH_CALLS = new LongAdder();
    private static final LongAdder WARM_START_ZERO_HITS = new LongAdder();
    private static final LongAdder SECOND_WARM_START_ZERO_HITS = new LongAdder();
    private static final LongAdder TREE_NODE_VISITS = new LongAdder();
    private static final LongAdder TREE_CHILD_DISTANCE_TESTS = new LongAdder();
    private static final LongAdder TREE_CHILD_ACCEPTS = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_0 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_1 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_2 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_3 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_4 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_5 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_6 = new LongAdder();
    private static final LongAdder TREE_VALID_CHILDREN_3_PLUS = new LongAdder();
    private static final LongAdder LINEAR_LEAF_TESTS = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_T = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_H = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_C = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_E = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_D = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_EXIT_W = new LongAdder();
    private static final LongAdder NO_OFFSET_CAP_NO_EARLY_EXIT = new LongAdder();
    // Tuned from vanilla debug dumps: D prunes the strongest, then T, with C/E next and W/H weakest.
    private static final String NO_OFFSET_CAP_ORDER = "D,T,C,E,W,H";

    private static volatile int LAST_NODE_COUNT;
    private static volatile int LAST_LEAF_COUNT;
    private static volatile int LAST_VALUE_COUNT;
    private static volatile int LAST_ACTIVE_DIMENSION_MASK;
    private static volatile int LAST_BOUNDS_BYTES;
    private static volatile boolean LAST_FULL_QUERY_DIMENSIONS;
    private static volatile boolean LAST_HAS_OFFSET_DISTANCES;
    private static volatile boolean LAST_LINEAR_SEARCH;

    // Read-only shared data
    private final long[] bounds;
    private final int[] structure; // [offset, count]
    private final int[] nodeOffsets;
    private final int[] nodeChildCounts;
    private final Object[] values;
    private final int rootIndex;
    private final int[] leafNodeIndices;
    private final int[] leafValueIndices;
    private final int singleLeafValueIndex;
    private final int activeDimensionMask;
    private final boolean fullQueryDimensions;
    private final boolean fullQueryNoOffset;
    private final int boundsStride;
    private final long[] offsetDistances;
    private final boolean hasOffsetDistances;
    private final boolean linearSearch;

    private static final int LINEAR_SEARCH_THRESHOLD =
            Integer.getInteger("ga.climate.linearSearchThreshold", 0);
    private static final boolean SECOND_WARM_START =
            Boolean.parseBoolean(System.getProperty("ga.climate.secondWarmStart", "true"));
    private static final boolean SORT_CHILDREN_BY_DISTANCE =
            Boolean.parseBoolean(System.getProperty("ga.climate.sortChildrenByDistance", "true"));
    private static final boolean ADAPTIVE_QUERY_CACHE =
            Boolean.parseBoolean(System.getProperty("ga.climate.adaptiveQueryCache", "true"));
    private static final int QUERY_CACHE_SIZE = queryCacheSize();
    private static final int QUERY_CACHE_MASK = QUERY_CACHE_SIZE - 1;
    private static final int QUERY_CACHE_DISABLE_PROBES =
            Integer.getInteger("ga.climate.queryCacheDisableProbes", 2048);
    private static final int QUERY_CACHE_DISABLE_HIT_RATE_SHIFT =
            Integer.getInteger("ga.climate.queryCacheDisableHitRateShift", 7);

    public record Stats(
            long indexBuilds,
            int lastNodeCount,
            int lastLeafCount,
            int lastValueCount,
            int lastBoundsBytes,
            int activeDimensionMask,
            boolean fullQueryDimensions,
            boolean hasOffsetDistances,
            boolean linearSearchIndex,
            int linearSearchThreshold,
            boolean adaptiveQueryCache,
            int queryCacheSize,
            int queryCacheDisableProbes,
            int queryCacheDisableHitRateShift,
            String noOffsetCapOrder,
            long searches,
            long lastValueCacheHits,
            long queryCacheProbes,
            long queryCacheHits,
            long queryCacheDisables,
            long linearSearchCalls,
            long treeSearchCalls,
            long warmStartZeroHits,
            long secondWarmStartZeroHits,
            long treeNodeVisits,
            long treeChildDistanceTests,
            long treeChildAccepts,
            long treeValidChildren0,
            long treeValidChildren1,
            long treeValidChildren2,
            long treeValidChildren3,
            long treeValidChildren4,
            long treeValidChildren5,
            long treeValidChildren6,
            long treeValidChildren3Plus,
            long linearLeafTests,
            long noOffsetCapExitT,
            long noOffsetCapExitH,
            long noOffsetCapExitC,
            long noOffsetCapExitE,
            long noOffsetCapExitD,
            long noOffsetCapExitW,
            long noOffsetCapNoEarlyExit
    ) {
    }

    /**
     * Thread-local search context to eliminate allocation overhead.
     * <p>Contains reusable buffers for DFS traversal stack (max 256 depth),
     * temporary arrays for child sorting, and warm-start tracking.
     * <p>Typical biome R-trees have depth < 10, so 256 provides ample headroom.
     */
    static final class SearchContext {
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
        boolean queryCacheEnabled = QUERY_CACHE_SIZE > 0;
        int queryCacheProbeCount;
        int queryCacheHitCount;
        int lastLeafNodeIndex = -1; // "Warm start" hint
        int lastValueIndex = -1;
        int previousLeafNodeIndex = -1;
        int previousValueIndex = -1;
        long lastT;
        long lastH;
        long lastC;
        long lastE;
        long lastD;
        long lastW;
    }

    private final ThreadLocal<SearchContext> ctx = ThreadLocal.withInitial(SearchContext::new);

    private static boolean statsEnabled() {
        return GeneratorAccelerator.isDevMode();
    }

    public static Stats snapshotStats() {
        return new Stats(
                INDEX_BUILDS.sum(),
                LAST_NODE_COUNT,
                LAST_LEAF_COUNT,
                LAST_VALUE_COUNT,
                LAST_BOUNDS_BYTES,
                LAST_ACTIVE_DIMENSION_MASK,
                LAST_FULL_QUERY_DIMENSIONS,
                LAST_HAS_OFFSET_DISTANCES,
                LAST_LINEAR_SEARCH,
                LINEAR_SEARCH_THRESHOLD,
                ADAPTIVE_QUERY_CACHE,
                QUERY_CACHE_SIZE,
                QUERY_CACHE_DISABLE_PROBES,
                QUERY_CACHE_DISABLE_HIT_RATE_SHIFT,
                NO_OFFSET_CAP_ORDER,
                SEARCHES.sum(),
                LAST_VALUE_CACHE_HITS.sum(),
                QUERY_CACHE_PROBES.sum(),
                QUERY_CACHE_HITS.sum(),
                QUERY_CACHE_DISABLES.sum(),
                LINEAR_SEARCH_CALLS.sum(),
                TREE_SEARCH_CALLS.sum(),
                WARM_START_ZERO_HITS.sum(),
                SECOND_WARM_START_ZERO_HITS.sum(),
                TREE_NODE_VISITS.sum(),
                TREE_CHILD_DISTANCE_TESTS.sum(),
                TREE_CHILD_ACCEPTS.sum(),
                TREE_VALID_CHILDREN_0.sum(),
                TREE_VALID_CHILDREN_1.sum(),
                TREE_VALID_CHILDREN_2.sum(),
                TREE_VALID_CHILDREN_3.sum(),
                TREE_VALID_CHILDREN_4.sum(),
                TREE_VALID_CHILDREN_5.sum(),
                TREE_VALID_CHILDREN_6.sum(),
                TREE_VALID_CHILDREN_3_PLUS.sum(),
                LINEAR_LEAF_TESTS.sum(),
                NO_OFFSET_CAP_EXIT_T.sum(),
                NO_OFFSET_CAP_EXIT_H.sum(),
                NO_OFFSET_CAP_EXIT_C.sum(),
                NO_OFFSET_CAP_EXIT_E.sum(),
                NO_OFFSET_CAP_EXIT_D.sum(),
                NO_OFFSET_CAP_EXIT_W.sum(),
                NO_OFFSET_CAP_NO_EARLY_EXIT.sum()
        );
    }

    public FlatClimateIndex(List<Pair<Climate.ParameterPoint, T>> entries) {
        if (entries.isEmpty())
            throw new IllegalArgumentException("Empty list");

        final Climate.RTree<T> vanillaTree = Climate.RTree.create(entries);
        final int estimatedSize = entries.size() * 2;

        final TempStorage storage = new TempStorage(estimatedSize);
        this.rootIndex = flatten(vanillaTree.root, storage);

        this.structure = Arrays.copyOf(storage.structure, storage.cursor * 2);
        this.nodeOffsets = collectNodeOffsets(storage.cursor);
        this.nodeChildCounts = collectNodeChildCounts(storage.cursor);
        this.values = storage.values.toArray();
        this.activeDimensionMask = collectActiveDimensionMask(storage.bounds, storage.cursor);
        this.fullQueryDimensions = (this.activeDimensionMask & QUERY_DIMENSION_MASK) == QUERY_DIMENSION_MASK;
        long[] candidateOffsetDistances = collectOffsetDistances(storage.bounds, storage.cursor);
        this.hasOffsetDistances = (this.activeDimensionMask & (1 << 6)) != 0 && hasNonZero(candidateOffsetDistances);
        this.fullQueryNoOffset = this.fullQueryDimensions && !this.hasOffsetDistances;
        this.offsetDistances = this.hasOffsetDistances ? candidateOffsetDistances : null;
        this.boundsStride = this.hasOffsetDistances ? BOUNDS_STRIDE_WITH_OFFSET : BOUNDS_STRIDE_NO_OFFSET;
        this.bounds = copyBounds(storage.bounds, storage.cursor, this.hasOffsetDistances);
        this.leafNodeIndices = collectLeafNodeIndices(storage.cursor);
        this.leafValueIndices = collectLeafValueIndices(this.leafNodeIndices);
        this.singleLeafValueIndex = this.leafValueIndices.length == 1 ? this.leafValueIndices[0] : -1;
        this.linearSearch = this.leafNodeIndices.length <= LINEAR_SEARCH_THRESHOLD;
        rememberIndexShape(storage.cursor);
    }

    public FlatClimateIndex(Climate.RTree<T> vanillaTree) {
        final TempStorage storage = new TempStorage(1024);
        this.rootIndex = flatten(vanillaTree.root, storage);

        this.structure = Arrays.copyOf(storage.structure, storage.cursor * 2);
        this.nodeOffsets = collectNodeOffsets(storage.cursor);
        this.nodeChildCounts = collectNodeChildCounts(storage.cursor);
        this.values = storage.values.toArray();
        this.activeDimensionMask = collectActiveDimensionMask(storage.bounds, storage.cursor);
        this.fullQueryDimensions = (this.activeDimensionMask & QUERY_DIMENSION_MASK) == QUERY_DIMENSION_MASK;
        long[] candidateOffsetDistances = collectOffsetDistances(storage.bounds, storage.cursor);
        this.hasOffsetDistances = (this.activeDimensionMask & (1 << 6)) != 0 && hasNonZero(candidateOffsetDistances);
        this.fullQueryNoOffset = this.fullQueryDimensions && !this.hasOffsetDistances;
        this.offsetDistances = this.hasOffsetDistances ? candidateOffsetDistances : null;
        this.boundsStride = this.hasOffsetDistances ? BOUNDS_STRIDE_WITH_OFFSET : BOUNDS_STRIDE_NO_OFFSET;
        this.bounds = copyBounds(storage.bounds, storage.cursor, this.hasOffsetDistances);
        this.leafNodeIndices = collectLeafNodeIndices(storage.cursor);
        this.leafValueIndices = collectLeafValueIndices(this.leafNodeIndices);
        this.singleLeafValueIndex = this.leafValueIndices.length == 1 ? this.leafValueIndices[0] : -1;
        this.linearSearch = this.leafNodeIndices.length <= LINEAR_SEARCH_THRESHOLD;
        rememberIndexShape(storage.cursor);
    }

    private void rememberIndexShape(int nodeCount) {
        if (!statsEnabled()) {
            return;
        }
        INDEX_BUILDS.increment();
        LAST_NODE_COUNT = nodeCount;
        LAST_LEAF_COUNT = this.leafNodeIndices.length;
        LAST_VALUE_COUNT = this.values.length;
        LAST_BOUNDS_BYTES = this.bounds.length * Long.BYTES;
        LAST_ACTIVE_DIMENSION_MASK = this.activeDimensionMask;
        LAST_FULL_QUERY_DIMENSIONS = this.fullQueryDimensions;
        LAST_HAS_OFFSET_DISTANCES = this.hasOffsetDistances;
        LAST_LINEAR_SEARCH = this.linearSearch;
    }

    private static long[] copyBounds(long[] sourceBounds, int nodeCount, boolean includeOffsetDimension) {
        int stride = includeOffsetDimension ? BOUNDS_STRIDE_WITH_OFFSET : BOUNDS_STRIDE_NO_OFFSET;
        if (includeOffsetDimension) {
            return Arrays.copyOf(sourceBounds, nodeCount * stride);
        }
        long[] compact = new long[nodeCount * stride];
        for (int node = 0; node < nodeCount; node++) {
            System.arraycopy(sourceBounds, node * BOUNDS_STRIDE_WITH_OFFSET,
                    compact, node * BOUNDS_STRIDE_NO_OFFSET,
                    BOUNDS_STRIDE_NO_OFFSET);
        }
        return compact;
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
        return search(this.ctx.get(), t, h, c, e, d, w);
    }

    SearchContext createSearchContext() {
        return new SearchContext();
    }

    T search(
           final SearchContext s,
           final long t,
           final long h,
           final long c,
           final long e,
           final long d,
           final long w
    ) {
        final boolean collectStats = statsEnabled();
        if (collectStats) {
            SEARCHES.increment();
        }
        if (this.singleLeafValueIndex >= 0) {
            return (T) values[this.singleLeafValueIndex];
        }
        if (s.lastValueIndex >= 0
                && s.lastT == t
                && s.lastH == h
                && s.lastC == c
                && s.lastE == e
                && s.lastD == d
                && s.lastW == w) {
            if (collectStats) {
                LAST_VALUE_CACHE_HITS.increment();
            }
            return (T) values[s.lastValueIndex];
        }

        int queryCacheSlot = -1;
        if (QUERY_CACHE_SIZE > 0 && s.queryCacheEnabled) {
            if (collectStats) {
                QUERY_CACHE_PROBES.increment();
            }
            s.queryCacheProbeCount++;
            queryCacheSlot = queryCacheSlot(t, h, c, e, d, w);
            int valuePlusOne = s.cacheValuePlusOne[queryCacheSlot];
            if (valuePlusOne != 0
                    && s.cacheT[queryCacheSlot] == t
                    && s.cacheH[queryCacheSlot] == h
                    && s.cacheC[queryCacheSlot] == c
                    && s.cacheE[queryCacheSlot] == e
                    && s.cacheD[queryCacheSlot] == d
                    && s.cacheW[queryCacheSlot] == w) {
                s.queryCacheHitCount++;
                if (collectStats) {
                    QUERY_CACHE_HITS.increment();
                }
                adaptQueryCache(s, collectStats, this.fullQueryNoOffset);
                int valueIndex = valuePlusOne - 1;
                rememberLastResult(s, s.cacheLeafNode[queryCacheSlot], valueIndex, t, h, c, e, d, w);
                return (T) values[valueIndex];
            }
            adaptQueryCache(s, collectStats, this.fullQueryNoOffset);
        }

        if (linearSearch) {
            if (collectStats) {
                LINEAR_SEARCH_CALLS.increment();
            }
            T result = searchLinear(s, collectStats, t, h, c, e, d, w);
            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
            return result;
        }

        if (collectStats) {
            TREE_SEARCH_CALLS.increment();
        }

        int bestLeafNodeIndex = -1;
        int bestLeafValueIndex = -1;
        long bestDist = Long.MAX_VALUE;

        /*
            If we've already searched for something, there's probably a new block nearby.
            We check the distance to the previous winner immediately.
        */
        if (s.lastLeafNodeIndex != -1) {
            bestLeafNodeIndex = s.lastLeafNodeIndex;
            bestDist = distance(bestLeafNodeIndex, t, h, c, e, d, w);

            /*
                the structure[node*2] for the sheet points to the index in the values array
             */
            bestLeafValueIndex = nodeOffsets[bestLeafNodeIndex];
            if (bestDist == 0L) {
                if (collectStats) {
                    WARM_START_ZERO_HITS.increment();
                }
                rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                return (T) values[bestLeafValueIndex];
            }
        }
        if (SECOND_WARM_START
                && s.previousLeafNodeIndex != -1
                && s.previousLeafNodeIndex != bestLeafNodeIndex) {
            int previousLeafNodeIndex = s.previousLeafNodeIndex;
            long previousDist = distance(previousLeafNodeIndex, t, h, c, e, d, w);
            if (previousDist < bestDist) {
                bestLeafNodeIndex = previousLeafNodeIndex;
                bestDist = previousDist;
                bestLeafValueIndex = s.previousValueIndex >= 0
                        ? s.previousValueIndex
                        : nodeOffsets[previousLeafNodeIndex];
                if (bestDist == 0L) {
                    if (collectStats) {
                        SECOND_WARM_START_ZERO_HITS.increment();
                    }
                    rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                    storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                    return (T) values[bestLeafValueIndex];
                }
            }
        }

        final int[] stack = s.stack;
        final long[] stackDistances = s.stackDistances;
        int sp = 0;
        final boolean fullQueryDimensions = this.fullQueryDimensions;
        final boolean fullQueryNoOffset = this.fullQueryNoOffset;
        long rootDistance = distanceCapped(rootIndex, t, h, c, e, d, w, bestDist);
        if (rootDistance >= bestDist && bestLeafValueIndex >= 0) {
            rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
            return (T) values[bestLeafValueIndex];
        }
        stack[sp] = rootIndex;
        stackDistances[sp++] = rootDistance;

        final long[] childDists = s.childDistances;
        final int[] childIdxs = s.childIndices;
        if (fullQueryNoOffset) {
            while (sp > 0) {
                --sp;
                int nodeIdx = stack[sp];
                long nodeDistance = stackDistances[sp];
                if (collectStats) {
                    TREE_NODE_VISITS.increment();
                }
                if (nodeDistance >= bestDist) {
                    continue;
                }

                final int offset = nodeOffsets[nodeIdx];
                final int childCount = nodeChildCounts[nodeIdx];
                if (childCount == 0) {
                    if (nodeDistance < bestDist) {
                        bestDist = nodeDistance;
                        bestLeafNodeIndex = nodeIdx;
                        bestLeafValueIndex = offset;
                        if (bestDist == 0L) {
                            rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                            return (T) values[bestLeafValueIndex];
                        }
                    }
                    continue;
                }

                int validChildren = 0;
                for (int i = 0; i < childCount; i++) {
                    final int childNodeIdx = offset + i;
                    final long dist = distanceCappedFullNoOffset(childNodeIdx, t, h, c, e, d, w, bestDist);
                    if (dist < bestDist) {
                        childDists[validChildren] = dist;
                        childIdxs[validChildren] = childNodeIdx;
                        validChildren++;
                    }
                }
                recordValidChildrenStats(collectStats, childCount, validChildren);
                sp = pushValidChildren(stack, stackDistances, sp, childIdxs, childDists, validChildren);
            }
        } else if (fullQueryDimensions) {
            while (sp > 0) {
                --sp;
                int nodeIdx = stack[sp];
                long nodeDistance = stackDistances[sp];
                if (collectStats) {
                    TREE_NODE_VISITS.increment();
                }
                if (nodeDistance >= bestDist) {
                    continue;
                }

                final int offset = nodeOffsets[nodeIdx];
                final int childCount = nodeChildCounts[nodeIdx];
                if (childCount == 0) {
                    if (nodeDistance < bestDist) {
                        bestDist = nodeDistance;
                        bestLeafNodeIndex = nodeIdx;
                        bestLeafValueIndex = offset;
                        if (bestDist == 0L) {
                            rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                            return (T) values[bestLeafValueIndex];
                        }
                    }
                    continue;
                }

                int validChildren = 0;
                for (int i = 0; i < childCount; i++) {
                    final int childNodeIdx = offset + i;
                    final long dist = distanceCappedFull(childNodeIdx, t, h, c, e, d, w, bestDist);
                    if (dist < bestDist) {
                        childDists[validChildren] = dist;
                        childIdxs[validChildren] = childNodeIdx;
                        validChildren++;
                    }
                }
                recordValidChildrenStats(collectStats, childCount, validChildren);
                sp = pushValidChildren(stack, stackDistances, sp, childIdxs, childDists, validChildren);
            }
        } else {
            while (sp > 0) {
                --sp;
                int nodeIdx = stack[sp];
                long nodeDistance = stackDistances[sp];
                if (collectStats) {
                    TREE_NODE_VISITS.increment();
                }
                if (nodeDistance >= bestDist) {
                    continue;
                }

                final int offset = nodeOffsets[nodeIdx];
                final int childCount = nodeChildCounts[nodeIdx];
                if (childCount == 0) {
                    if (nodeDistance < bestDist) {
                        bestDist = nodeDistance;
                        bestLeafNodeIndex = nodeIdx;
                        bestLeafValueIndex = offset;
                        if (bestDist == 0L) {
                            rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                            storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
                            return (T) values[bestLeafValueIndex];
                        }
                    }
                    continue;
                }

                int validChildren = 0;
                for (int i = 0; i < childCount; i++) {
                    final int childNodeIdx = offset + i;
                    final long dist = distanceMaskedCapped(childNodeIdx, t, h, c, e, d, w, bestDist);
                    if (dist < bestDist) {
                        childDists[validChildren] = dist;
                        childIdxs[validChildren] = childNodeIdx;
                        validChildren++;
                    }
                }
                recordValidChildrenStats(collectStats, childCount, validChildren);
                sp = pushValidChildren(stack, stackDistances, sp, childIdxs, childDists, validChildren);
            }
        }

        rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
        storeQueryCache(s, queryCacheSlot, t, h, c, e, d, w);
        return (T) values[bestLeafValueIndex];
    }

    private static void recordValidChildrenStats(boolean collectStats, int childCount, int validChildren) {
        if (!collectStats) {
            return;
        }
        TREE_CHILD_DISTANCE_TESTS.add(childCount);
        TREE_CHILD_ACCEPTS.add(validChildren);
        switch (validChildren) {
            case 0 -> TREE_VALID_CHILDREN_0.increment();
            case 1 -> TREE_VALID_CHILDREN_1.increment();
            case 2 -> TREE_VALID_CHILDREN_2.increment();
            case 3 -> {
                TREE_VALID_CHILDREN_3.increment();
                TREE_VALID_CHILDREN_3_PLUS.increment();
            }
            case 4 -> {
                TREE_VALID_CHILDREN_4.increment();
                TREE_VALID_CHILDREN_3_PLUS.increment();
            }
            case 5 -> {
                TREE_VALID_CHILDREN_5.increment();
                TREE_VALID_CHILDREN_3_PLUS.increment();
            }
            case 6 -> {
                TREE_VALID_CHILDREN_6.increment();
                TREE_VALID_CHILDREN_3_PLUS.increment();
            }
            default -> TREE_VALID_CHILDREN_3_PLUS.increment();
        }
    }

    private static int pushValidChildren(
            int[] stack,
            long[] stackDistances,
            int sp,
            int[] childIdxs,
            long[] childDists,
            int validChildren
    ) {
        if (validChildren == 0) {
            return sp;
        }
        if (validChildren == 1) {
            stack[sp] = childIdxs[0];
            stackDistances[sp++] = childDists[0];
            return sp;
        }
        if (validChildren == 2) {
            long firstDist = childDists[0];
            long secondDist = childDists[1];
            if (firstDist < secondDist) {
                stack[sp] = childIdxs[1];
                stackDistances[sp++] = secondDist;
                stack[sp] = childIdxs[0];
                stackDistances[sp++] = firstDist;
            } else {
                stack[sp] = childIdxs[0];
                stackDistances[sp++] = firstDist;
                stack[sp] = childIdxs[1];
                stackDistances[sp++] = secondDist;
            }
            return sp;
        }
        if (validChildren == 3) {
            pushSorted3(stack, stackDistances, sp, childIdxs, childDists);
            return sp + 3;
        }
        if (validChildren == 4) {
            pushSorted4(stack, stackDistances, sp, childIdxs, childDists);
            return sp + 4;
        }
        if (validChildren == 5) {
            pushSorted5(stack, stackDistances, sp, childIdxs, childDists);
            return sp + 5;
        }
        if (validChildren == 6) {
            pushSorted6(stack, stackDistances, sp, childIdxs, childDists);
            return sp + 6;
        }

        if (SORT_CHILDREN_BY_DISTANCE) {
            for (int i = 1; i < validChildren; i++) {
                long dist = childDists[i];
                int idx = childIdxs[i];
                int j = i - 1;
                while (j >= 0 && childDists[j] > dist) {
                    childDists[j + 1] = childDists[j];
                    childIdxs[j + 1] = childIdxs[j];
                    j--;
                }
                childDists[j + 1] = dist;
                childIdxs[j + 1] = idx;
            }
            for (int i = validChildren - 1; i >= 0; i--) {
                stack[sp] = childIdxs[i];
                stackDistances[sp++] = childDists[i];
            }
            return sp;
        }

        int nearest = 0;
        long nearestDist = childDists[0];
        for (int i = 1; i < validChildren; i++) {
            long dist = childDists[i];
            if (dist < nearestDist) {
                nearest = i;
                nearestDist = dist;
            }
        }
        for (int i = 0; i < validChildren; i++) {
            if (i != nearest) {
                stack[sp] = childIdxs[i];
                stackDistances[sp++] = childDists[i];
            }
        }
        stack[sp] = childIdxs[nearest];
        stackDistances[sp++] = nearestDist;
        return sp;
    }

    private T searchLinear(
            SearchContext s,
            boolean collectStats,
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
                rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                return (T) values[bestLeafValueIndex];
            }
        }
        int previousLeafNodeIndex = SECOND_WARM_START ? s.previousLeafNodeIndex : -1;
        if (previousLeafNodeIndex != -1 && previousLeafNodeIndex != bestLeafNodeIndex) {
            long previousDist = distance(previousLeafNodeIndex, t, h, c, e, d, w);
            if (previousDist < bestDist) {
                bestDist = previousDist;
                bestLeafNodeIndex = previousLeafNodeIndex;
                bestLeafValueIndex = s.previousValueIndex >= 0
                        ? s.previousValueIndex
                        : nodeOffsets[previousLeafNodeIndex];
                if (bestDist == 0L) {
                    rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                    return (T) values[bestLeafValueIndex];
                }
            }
        }

        int[] leaves = this.leafNodeIndices;
        int[] leafValues = this.leafValueIndices;
        int testedLeaves = 0;
        for (int i = 0; i < leaves.length; i++) {
            int leaf = leaves[i];
            if (leaf == s.lastLeafNodeIndex || leaf == previousLeafNodeIndex) {
                continue;
            }
            testedLeaves++;
            long dist = distance(leaf, t, h, c, e, d, w);
            if (dist < bestDist) {
                bestDist = dist;
                bestLeafNodeIndex = leaf;
                bestLeafValueIndex = leafValues[i];
                if (bestDist == 0L) {
                    rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
                    return (T) values[bestLeafValueIndex];
                }
            }
        }
        if (collectStats) {
            LINEAR_LEAF_TESTS.add(testedLeaves);
        }

        rememberLastResult(s, bestLeafNodeIndex, bestLeafValueIndex, t, h, c, e, d, w);
        return (T) values[bestLeafValueIndex];
    }

    private static void pushSorted3(
            int[] stack,
            long[] stackDistances,
            int sp,
            int[] childIdxs,
            long[] childDists
    ) {
        sortPairAscending(childIdxs, childDists, 0, 1);
        sortPairAscending(childIdxs, childDists, 1, 2);
        sortPairAscending(childIdxs, childDists, 0, 1);
        stack[sp] = childIdxs[2];
        stackDistances[sp++] = childDists[2];
        stack[sp] = childIdxs[1];
        stackDistances[sp++] = childDists[1];
        stack[sp] = childIdxs[0];
        stackDistances[sp] = childDists[0];
    }

    private static void pushSorted4(
            int[] stack,
            long[] stackDistances,
            int sp,
            int[] childIdxs,
            long[] childDists
    ) {
        sortPairAscending(childIdxs, childDists, 0, 1);
        sortPairAscending(childIdxs, childDists, 2, 3);
        sortPairAscending(childIdxs, childDists, 0, 2);
        sortPairAscending(childIdxs, childDists, 1, 3);
        sortPairAscending(childIdxs, childDists, 1, 2);
        stack[sp] = childIdxs[3];
        stackDistances[sp++] = childDists[3];
        stack[sp] = childIdxs[2];
        stackDistances[sp++] = childDists[2];
        stack[sp] = childIdxs[1];
        stackDistances[sp++] = childDists[1];
        stack[sp] = childIdxs[0];
        stackDistances[sp] = childDists[0];
    }

    private static void pushSorted5(
            int[] stack,
            long[] stackDistances,
            int sp,
            int[] childIdxs,
            long[] childDists
    ) {
        sortPairAscending(childIdxs, childDists, 0, 1);
        sortPairAscending(childIdxs, childDists, 3, 4);
        sortPairAscending(childIdxs, childDists, 2, 4);
        sortPairAscending(childIdxs, childDists, 2, 3);
        sortPairAscending(childIdxs, childDists, 1, 4);
        sortPairAscending(childIdxs, childDists, 0, 3);
        sortPairAscending(childIdxs, childDists, 0, 2);
        sortPairAscending(childIdxs, childDists, 1, 3);
        sortPairAscending(childIdxs, childDists, 1, 2);
        stack[sp] = childIdxs[4];
        stackDistances[sp++] = childDists[4];
        stack[sp] = childIdxs[3];
        stackDistances[sp++] = childDists[3];
        stack[sp] = childIdxs[2];
        stackDistances[sp++] = childDists[2];
        stack[sp] = childIdxs[1];
        stackDistances[sp++] = childDists[1];
        stack[sp] = childIdxs[0];
        stackDistances[sp] = childDists[0];
    }

    private static void pushSorted6(
            int[] stack,
            long[] stackDistances,
            int sp,
            int[] childIdxs,
            long[] childDists
    ) {
        sortPairAscending(childIdxs, childDists, 1, 2);
        sortPairAscending(childIdxs, childDists, 4, 5);
        sortPairAscending(childIdxs, childDists, 0, 2);
        sortPairAscending(childIdxs, childDists, 3, 5);
        sortPairAscending(childIdxs, childDists, 0, 1);
        sortPairAscending(childIdxs, childDists, 3, 4);
        sortPairAscending(childIdxs, childDists, 2, 5);
        sortPairAscending(childIdxs, childDists, 0, 3);
        sortPairAscending(childIdxs, childDists, 1, 4);
        sortPairAscending(childIdxs, childDists, 2, 4);
        sortPairAscending(childIdxs, childDists, 1, 3);
        sortPairAscending(childIdxs, childDists, 2, 3);
        stack[sp] = childIdxs[5];
        stackDistances[sp++] = childDists[5];
        stack[sp] = childIdxs[4];
        stackDistances[sp++] = childDists[4];
        stack[sp] = childIdxs[3];
        stackDistances[sp++] = childDists[3];
        stack[sp] = childIdxs[2];
        stackDistances[sp++] = childDists[2];
        stack[sp] = childIdxs[1];
        stackDistances[sp++] = childDists[1];
        stack[sp] = childIdxs[0];
        stackDistances[sp] = childDists[0];
    }

    private static void sortPairAscending(int[] childIdxs, long[] childDists, int left, int right) {
        if (childDists[left] > childDists[right]) {
            long dist = childDists[left];
            childDists[left] = childDists[right];
            childDists[right] = dist;
            int idx = childIdxs[left];
            childIdxs[left] = childIdxs[right];
            childIdxs[right] = idx;
        }
    }

    private static void rememberLastResult(
            SearchContext s,
            int leafNodeIndex,
            int valueIndex,
            long t,
            long h,
            long c,
            long e,
            long d,
            long w
    ) {
        if (leafNodeIndex >= 0 && s.lastLeafNodeIndex != leafNodeIndex) {
            if (s.lastLeafNodeIndex >= 0 && s.lastValueIndex >= 0) {
                s.previousLeafNodeIndex = s.lastLeafNodeIndex;
                s.previousValueIndex = s.lastValueIndex;
            }
            s.lastLeafNodeIndex = leafNodeIndex;
        }
        s.lastValueIndex = valueIndex;
        s.lastT = t;
        s.lastH = h;
        s.lastC = c;
        s.lastE = e;
        s.lastD = d;
        s.lastW = w;
    }

    private static int queryCacheSize() {
        int configured = Integer.getInteger("ga.climate.queryCacheSize", 64);
        if (configured <= 0) {
            return 0;
        }
        return Integer.highestOneBit(configured);
    }

    private static int queryCacheSlot(long t, long h, long c, long e, long d, long w) {
        long mixed = t;
        mixed += h * 31L;
        mixed += c * 313L;
        mixed += e * 3133L;
        mixed += d * 31337L;
        mixed += w * 313373L;
        mixed ^= mixed >>> 32;
        return (int) mixed & QUERY_CACHE_MASK;
    }

    private static void adaptQueryCache(SearchContext s, boolean collectStats, boolean allowDisable) {
        if (!ADAPTIVE_QUERY_CACHE || !allowDisable || !s.queryCacheEnabled) {
            return;
        }
        if (s.queryCacheProbeCount < QUERY_CACHE_DISABLE_PROBES) {
            return;
        }
        // Disable only when the cache stayed completely useless through the whole probation window.
        if (s.queryCacheHitCount != 0) {
            return;
        }
        s.queryCacheEnabled = false;
        if (collectStats) {
            QUERY_CACHE_DISABLES.increment();
        }
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
        if (this.fullQueryNoOffset) {
            return distanceFullNoOffset(nodeIdx, t, h, c, e, d, w);
        }
        if (!this.fullQueryDimensions) {
            return distanceMasked(nodeIdx, t, h, c, e, d, w);
        }
        int base = nodeIdx * boundsStride;
        long dist = 0L;
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

    private long distanceFullNoOffset(int nodeIdx, long t, long h, long c, long e, long d, long w) {
        int base = nodeIdx * boundsStride;
        long dist = 0L;
        dist += bDist(bounds[base], bounds[base + 1], t);
        dist += bDist(bounds[base + 2], bounds[base + 3], h);
        dist += bDist(bounds[base + 4], bounds[base + 5], c);
        dist += bDist(bounds[base + 6], bounds[base + 7], e);
        dist += bDist(bounds[base + 8], bounds[base + 9], d);
        dist += bDist(bounds[base + 10], bounds[base + 11], w);
        return dist;
    }

    private long distanceCapped(int nodeIdx, long t, long h, long c, long e, long d, long w, long cap) {
        if (this.fullQueryNoOffset) {
            return distanceCappedFullNoOffset(nodeIdx, t, h, c, e, d, w, cap);
        }
        if (!this.fullQueryDimensions) {
            return distanceMaskedCapped(nodeIdx, t, h, c, e, d, w, cap);
        }
        return distanceCappedFull(nodeIdx, t, h, c, e, d, w, cap);
    }

    private long distanceCappedFullNoOffset(int nodeIdx, long t, long h, long c, long e, long d, long w, long cap) {
        int base = nodeIdx * boundsStride;
        long dist = 0L;
        // Keep this order aligned with NO_OFFSET_CAP_ORDER and retune only from fresh biome stats.
        dist += bDist(bounds[base + 8], bounds[base + 9], d);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_D.increment();
            }
            return cap;
        }
        dist += bDist(bounds[base], bounds[base + 1], t);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_T.increment();
            }
            return cap;
        }
        dist += bDist(bounds[base + 4], bounds[base + 5], c);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_C.increment();
            }
            return cap;
        }
        dist += bDist(bounds[base + 6], bounds[base + 7], e);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_E.increment();
            }
            return cap;
        }
        dist += bDist(bounds[base + 10], bounds[base + 11], w);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_W.increment();
            }
            return cap;
        }
        dist += bDist(bounds[base + 2], bounds[base + 3], h);
        if (dist >= cap) {
            if (statsEnabled()) {
                NO_OFFSET_CAP_EXIT_H.increment();
            }
            return cap;
        }
        if (statsEnabled()) {
            NO_OFFSET_CAP_NO_EARLY_EXIT.increment();
        }
        return dist;
    }

    private long distanceCappedFull(int nodeIdx, long t, long h, long c, long e, long d, long w, long cap) {
        int base = nodeIdx * boundsStride;
        long dist = 0L;
        dist += bDist(bounds[base], bounds[base + 1], t);
        if (dist >= cap) return cap;
        dist += bDist(bounds[base + 2], bounds[base + 3], h);
        if (dist >= cap) return cap;
        dist += bDist(bounds[base + 4], bounds[base + 5], c);
        if (dist >= cap) return cap;
        dist += bDist(bounds[base + 6], bounds[base + 7], e);
        if (dist >= cap) return cap;
        dist += bDist(bounds[base + 8], bounds[base + 9], d);
        if (dist >= cap) return cap;
        dist += bDist(bounds[base + 10], bounds[base + 11], w);
        if (hasOffsetDistances) {
            dist += offsetDistances[nodeIdx];
        }
        return dist >= cap ? cap : dist;
    }

    private long distanceMasked(int nodeIdx, long t, long h, long c, long e, long d, long w) {
        int base = nodeIdx * boundsStride;
        int mask = this.activeDimensionMask;
        long dist = 0L;
        if ((mask & 1) != 0) dist += bDist(bounds[base], bounds[base + 1], t);
        if ((mask & 2) != 0) dist += bDist(bounds[base + 2], bounds[base + 3], h);
        if ((mask & 4) != 0) dist += bDist(bounds[base + 4], bounds[base + 5], c);
        if ((mask & 8) != 0) dist += bDist(bounds[base + 6], bounds[base + 7], e);
        if ((mask & 16) != 0) dist += bDist(bounds[base + 8], bounds[base + 9], d);
        if ((mask & 32) != 0) dist += bDist(bounds[base + 10], bounds[base + 11], w);
        if (hasOffsetDistances) {
            dist += offsetDistances[nodeIdx];
        }
        return dist;
    }

    private long distanceMaskedCapped(int nodeIdx, long t, long h, long c, long e, long d, long w, long cap) {
        int base = nodeIdx * boundsStride;
        int mask = this.activeDimensionMask;
        long dist = 0L;
        if ((mask & 1) != 0) {
            dist += bDist(bounds[base], bounds[base + 1], t);
            if (dist >= cap) return cap;
        }
        if ((mask & 2) != 0) {
            dist += bDist(bounds[base + 2], bounds[base + 3], h);
            if (dist >= cap) return cap;
        }
        if ((mask & 4) != 0) {
            dist += bDist(bounds[base + 4], bounds[base + 5], c);
            if (dist >= cap) return cap;
        }
        if ((mask & 8) != 0) {
            dist += bDist(bounds[base + 6], bounds[base + 7], e);
            if (dist >= cap) return cap;
        }
        if ((mask & 16) != 0) {
            dist += bDist(bounds[base + 8], bounds[base + 9], d);
            if (dist >= cap) return cap;
        }
        if ((mask & 32) != 0) {
            dist += bDist(bounds[base + 10], bounds[base + 11], w);
            if (dist >= cap) return cap;
        }
        if (hasOffsetDistances) {
            dist += offsetDistances[nodeIdx];
        }
        return dist >= cap ? cap : dist;
    }

    /**
     * Square of distance to interval. <br>
     * logic: max(0, val - max) + max(0, min - val) using bit hacks
     */
    private static long bDist(long min, long max, long val) {
        long d1 = Math.max(0L, val - max);
        long d2 = Math.max(0L, min - val);
        long d = d1 + d2;
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

    private long[] collectOffsetDistances(long[] sourceBounds, int nodeCount) {
        long[] distances = new long[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            int base = i * BOUNDS_STRIDE_WITH_OFFSET;
            distances[i] = bDist(sourceBounds[base + 12], sourceBounds[base + 13], 0);
        }
        return distances;
    }

    private int collectActiveDimensionMask(long[] sourceBounds, int nodeCount) {
        int mask = 0;
        for (int dimension = 0; dimension < PARAMS; dimension++) {
            if (dimensionVaries(sourceBounds, nodeCount, dimension)) {
                mask |= 1 << dimension;
            }
        }
        return mask;
    }

    private boolean dimensionVaries(long[] sourceBounds, int nodeCount, int dimension) {
        int offset = dimension * 2;
        long min = sourceBounds[offset];
        long max = sourceBounds[offset + 1];
        for (int node = 1; node < nodeCount; node++) {
            int base = node * BOUNDS_STRIDE_WITH_OFFSET + offset;
            if (sourceBounds[base] != min || sourceBounds[base + 1] != max) {
                return true;
            }
        }
        return false;
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

        final int base = index * BOUNDS_STRIDE_WITH_OFFSET;
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
        final int base = index * BOUNDS_STRIDE_WITH_OFFSET;
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
            this.bounds = new long[size * BOUNDS_STRIDE_WITH_OFFSET];
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
