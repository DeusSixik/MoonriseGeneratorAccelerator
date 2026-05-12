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
            Integer.highestOneBit(Math.max(2, Integer.getInteger("ga.terrablender.uniquenessCacheSize", 64) - 1)) << 1;

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
        private final long[] uniquenessKeys = new long[UNIQUENESS_CACHE_SIZE];
        private final int[] uniquenessValues = new int[UNIQUENESS_CACHE_SIZE];
        private final boolean[] uniquenessPresent = new boolean[UNIQUENESS_CACHE_SIZE];

        ParameterListCache bind(IExtendedParameterList<?> list) {
            int treeCount = list.getTreeCount();
            if (this.parameterList != list || this.trees.length != treeCount) {
                this.parameterList = list;
                this.trees = new Climate.RTree[treeCount];
                this.indexes = new FlatClimateIndex[treeCount];
                for (int i = 0; i < this.uniquenessPresent.length; i++) {
                    this.uniquenessPresent[i] = false;
                }
            }
            return this;
        }

        int getUniqueness(int x, int z) {
            long key = (((long) x) << 32) ^ (z & 0xFFFF_FFFFL);
            int slot = mix(key) & (this.uniquenessKeys.length - 1);
            if (this.uniquenessPresent[slot] && this.uniquenessKeys[slot] == key) {
                return this.uniquenessValues[slot];
            }
            int uniqueness = this.parameterList.getUniqueness(x, 0, z);
            this.uniquenessPresent[slot] = true;
            this.uniquenessKeys[slot] = key;
            this.uniquenessValues[slot] = uniqueness;
            return uniqueness;
        }

        Object search(int uniqueness, long t, long h, long c, long e, long d, long w) {
            if (uniqueness < 0 || uniqueness >= this.indexes.length) {
                uniqueness = 0;
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
            return flatIndex.search(t, h, c, e, d, w);
        }

        private static int mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }
    }
}
