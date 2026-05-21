package dev.sixik.generator_accelerator.common.aquifer.region;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact 4x4-region cache for aquifer sample-point fluids.
 *
 * <p>Entries are keyed by the shared aquifer owner and the sample-point block
 * position. Neighboring chunks often load the same aquifer sample points, so
 * sharing them at the region level removes repeated {@code computeFluid(...)}
 * work without changing the resulting fluid status.</p>
 */
public final class GARegionalAquiferSampleCache {
    public static final int REGION_BLOCK_SHIFT = 6;

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.aquifer.regionalSampleCache.enabled", "false")
    );
    private static final int MAX_REGIONS = Math.max(
            16,
            Integer.getInteger("ga.aquifer.regionalSampleCache.maxRegions", 256)
    );

    private static final ConcurrentHashMap<RegionKey, RegionEntry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<RegionKey> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REGION_COUNT = new AtomicInteger();

    private GARegionalAquiferSampleCache() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static Sample get(
            GARegionalAquiferCacheOwner owner,
            int sampleX,
            int sampleY,
            int sampleZ
    ) {
        if (!ENABLED || owner == null) {
            return null;
        }
        RegionEntry region = CACHE.get(new RegionKey(owner, sampleX >> REGION_BLOCK_SHIFT, sampleZ >> REGION_BLOCK_SHIFT));
        return region == null ? null : region.samples.get(BlockPos.asLong(sampleX, sampleY, sampleZ));
    }

    public static void putIfAbsent(
            GARegionalAquiferCacheOwner owner,
            int sampleX,
            int sampleY,
            int sampleZ,
            Sample sample
    ) {
        if (!ENABLED || owner == null || sample == null) {
            return;
        }
        RegionKey key = new RegionKey(owner, sampleX >> REGION_BLOCK_SHIFT, sampleZ >> REGION_BLOCK_SHIFT);
        RegionEntry region = CACHE.get(key);
        if (region == null) {
            RegionEntry created = new RegionEntry();
            RegionEntry existing = CACHE.putIfAbsent(key, created);
            if (existing == null) {
                region = created;
                INSERTION_ORDER.offer(key);
                REGION_COUNT.incrementAndGet();
                evictIfNeeded();
            } else {
                region = existing;
            }
        }
        region.samples.putIfAbsent(BlockPos.asLong(sampleX, sampleY, sampleZ), sample);
    }

    static void clearForTests() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        REGION_COUNT.set(0);
    }

    static int regionCountForTests() {
        return REGION_COUNT.get();
    }

    private static void evictIfNeeded() {
        while (REGION_COUNT.get() > MAX_REGIONS) {
            RegionKey oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            if (CACHE.remove(oldest) != null) {
                REGION_COUNT.decrementAndGet();
            }
        }
    }

    public record Sample(int fluidLevel, byte fluidKind, int blockId) {
    }

    private static final class RegionEntry {
        private final ConcurrentHashMap<Long, Sample> samples = new ConcurrentHashMap<>();
    }

    private static final class RegionKey {
        private final GARegionalAquiferCacheOwner owner;
        private final int regionX;
        private final int regionZ;
        private final int hash;

        private RegionKey(GARegionalAquiferCacheOwner owner, int regionX, int regionZ) {
            this.owner = owner;
            this.regionX = regionX;
            this.regionZ = regionZ;

            int result = owner.hashCode();
            result = 31 * result + regionX;
            result = 31 * result + regionZ;
            this.hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegionKey that)) {
                return false;
            }
            return this.regionX == that.regionX
                    && this.regionZ == that.regionZ
                    && this.owner.equals(that.owner);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
