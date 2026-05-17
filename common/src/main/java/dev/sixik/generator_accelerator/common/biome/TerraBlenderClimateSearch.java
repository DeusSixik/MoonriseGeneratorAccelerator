package dev.sixik.generator_accelerator.common.biome;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.worldgen.IExtendedParameterList;

public final class TerraBlenderClimateSearch {
    private static final Cache<Climate.RTree<?>, FlatClimateIndex<?>> TREE_CACHE = Caffeine.newBuilder()
            .initialCapacity(16)
            .weakKeys()
            .build();
    private static final int UNIQUENESS_CACHE_SIZE =
            Integer.highestOneBit(Math.max(2, Integer.getInteger("ga.terrablender.uniquenessCacheSize", 256) - 1)) << 1;

    private static final ThreadLocal<TreeCache> LAST_TREE = ThreadLocal.withInitial(TreeCache::new);
    private static final ThreadLocal<ParameterListCache> LAST_PARAMETER_LIST =
            ThreadLocal.withInitial(ParameterListCache::new);

    private TerraBlenderClimateSearch() {
    }

    @SuppressWarnings("unchecked")
    public static Holder<Biome> findRaw(IExtendedParameterList<?> parameterList, long[] target, int x, int y, int z) {
        ParameterListCache cache = LAST_PARAMETER_LIST.get().bind(parameterList);
        int uniqueness = cache.getUniqueness(x, z);
        long t = target[0];
        long h = target[1];
        long c = target[2];
        long e = target[3];
        long d = target[4];
        long w = target[5];
        Holder<Biome> biome = (Holder<Biome>) cache.search(uniqueness, t, h, c, e, d, w);
        if (uniqueness != 0 && biome.is(Region.DEFERRED_PLACEHOLDER)) {
            biome = (Holder<Biome>) cache.search(0, t, h, c, e, d, w);
        }
        return biome;
    }

    public static Object search(Climate.RTree<?> tree, Climate.TargetPoint target) {
        return search(
                tree,
                target.temperature(),
                target.humidity(),
                target.continentalness(),
                target.erosion(),
                target.depth(),
                target.weirdness()
        );
    }

    public static Object search(Climate.RTree<?> tree, long[] target) {
        return search(tree, target[0], target[1], target[2], target[3], target[4], target[5]);
    }

    private static Object search(Climate.RTree<?> tree, long t, long h, long c, long e, long d, long w) {
        TreeCache last = LAST_TREE.get();
        FlatClimateIndex<?> flatIndex = last.flatIndex;
        if (last.tree != tree) {
            flatIndex = TREE_CACHE.get(tree, FlatClimateIndex::new);
            last.tree = tree;
            last.flatIndex = flatIndex;
        }
        return flatIndex.search(t, h, c, e, d, w);
    }

    private static final class TreeCache {
        Climate.RTree<?> tree;
        FlatClimateIndex<?> flatIndex;
    }

    private static final class ParameterListCache {
        private IExtendedParameterList<?> parameterList;
        private Climate.RTree<?>[] trees = new Climate.RTree[0];
        private FlatClimateIndex<?>[] indexes = new FlatClimateIndex[0];
        private FlatClimateIndex.SearchContext[] contexts = new FlatClimateIndex.SearchContext[0];
        private final long[] uniquenessKeys = new long[UNIQUENESS_CACHE_SIZE];
        private final int[] uniquenessValues = new int[UNIQUENESS_CACHE_SIZE];
        private final boolean[] uniquenessPresent = new boolean[UNIQUENESS_CACHE_SIZE];
        private boolean lastUniquenessPresent;
        private int lastUniquenessX;
        private int lastUniquenessZ;
        private int lastUniquenessValue;
        private int lastSearchUniqueness = -1;
        private FlatClimateIndex<?> lastSearchIndex;
        private FlatClimateIndex.SearchContext lastSearchContext;
        private int previousSearchUniqueness = -1;
        private FlatClimateIndex<?> previousSearchIndex;
        private FlatClimateIndex.SearchContext previousSearchContext;

        ParameterListCache bind(IExtendedParameterList<?> list) {
            if (this.parameterList != list) {
                int treeCount = list.getTreeCount();
                this.parameterList = list;
                this.trees = new Climate.RTree[treeCount];
                this.indexes = new FlatClimateIndex[treeCount];
                this.contexts = new FlatClimateIndex.SearchContext[treeCount];
                for (int i = 0; i < this.uniquenessPresent.length; i++) {
                    this.uniquenessPresent[i] = false;
                }
                this.lastUniquenessPresent = false;
                this.lastSearchUniqueness = -1;
                this.lastSearchIndex = null;
                this.lastSearchContext = null;
                this.previousSearchUniqueness = -1;
                this.previousSearchIndex = null;
                this.previousSearchContext = null;
            }
            return this;
        }

        int getUniqueness(int x, int z) {
            if (this.lastUniquenessPresent && this.lastUniquenessX == x && this.lastUniquenessZ == z) {
                return this.lastUniquenessValue;
            }
            long key = (((long) x) << 32) ^ (z & 0xFFFF_FFFFL);
            int slot = mix(key) & (this.uniquenessKeys.length - 1);
            if (this.uniquenessPresent[slot] && this.uniquenessKeys[slot] == key) {
                int uniqueness = this.uniquenessValues[slot];
                rememberUniqueness(x, z, uniqueness);
                return uniqueness;
            }
            int uniqueness = this.parameterList.getUniqueness(x, 0, z);
            this.uniquenessPresent[slot] = true;
            this.uniquenessKeys[slot] = key;
            this.uniquenessValues[slot] = uniqueness;
            rememberUniqueness(x, z, uniqueness);
            return uniqueness;
        }

        Object search(int uniqueness, long t, long h, long c, long e, long d, long w) {
            if (uniqueness < 0 || uniqueness >= this.indexes.length) {
                uniqueness = 0;
            }
            if (this.lastSearchUniqueness == uniqueness) {
                return this.lastSearchIndex.search(this.lastSearchContext, t, h, c, e, d, w);
            }
            if (this.previousSearchUniqueness == uniqueness) {
                FlatClimateIndex<?> flatIndex = this.previousSearchIndex;
                FlatClimateIndex.SearchContext context = this.previousSearchContext;
                this.previousSearchUniqueness = this.lastSearchUniqueness;
                this.previousSearchIndex = this.lastSearchIndex;
                this.previousSearchContext = this.lastSearchContext;
                this.lastSearchUniqueness = uniqueness;
                this.lastSearchIndex = flatIndex;
                this.lastSearchContext = context;
                return flatIndex.search(context, t, h, c, e, d, w);
            }

            FlatClimateIndex<?> flatIndex = this.indexes[uniqueness];
            if (flatIndex == null) {
                Climate.RTree<?> tree = this.trees[uniqueness];
                if (tree == null) {
                    tree = this.parameterList.getTree(uniqueness);
                    this.trees[uniqueness] = tree;
                }
                flatIndex = TREE_CACHE.get(tree, FlatClimateIndex::new);
                this.indexes[uniqueness] = flatIndex;
            }
            FlatClimateIndex.SearchContext context = this.contexts[uniqueness];
            if (context == null) {
                context = flatIndex.createSearchContext();
                this.contexts[uniqueness] = context;
            }
            rememberIndex(uniqueness, flatIndex, context);
            return flatIndex.search(context, t, h, c, e, d, w);
        }

        private void rememberUniqueness(int x, int z, int uniqueness) {
            this.lastUniquenessPresent = true;
            this.lastUniquenessX = x;
            this.lastUniquenessZ = z;
            this.lastUniquenessValue = uniqueness;
        }

        private void rememberIndex(
                int uniqueness,
                FlatClimateIndex<?> flatIndex,
                FlatClimateIndex.SearchContext context
        ) {
            this.previousSearchUniqueness = this.lastSearchUniqueness;
            this.previousSearchIndex = this.lastSearchIndex;
            this.previousSearchContext = this.lastSearchContext;
            this.lastSearchUniqueness = uniqueness;
            this.lastSearchIndex = flatIndex;
            this.lastSearchContext = context;
        }

        private static int mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }
    }
}
