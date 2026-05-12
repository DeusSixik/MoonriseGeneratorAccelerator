package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineMetrics;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class BiomeSignatureFeatureMaskCache {
    private static final int DEFAULT_CAPACITY = 64;

    private final AtomicReferenceArray<Entry> entries;
    private final AtomicInteger filledSlots = new AtomicInteger();

    public BiomeSignatureFeatureMaskCache() {
        this(DEFAULT_CAPACITY);
    }

    BiomeSignatureFeatureMaskCache(int capacity) {
        this.entries = new AtomicReferenceArray<>(Math.max(1, capacity));
    }

    public boolean copyIfPresent(
            ObjectArraySet<Holder<Biome>> biomes,
            BiomeDecorationScratch scratch,
            int stepCount,
            int[] wordCountsByStep
    ) {
        int biomeCount = biomes.size();
        if (biomeCount == 0) {
            return false;
        }

        long hashA = 0L;
        long hashB = 0L;
        for (Holder<Biome> biome : biomes) {
            long mixed = mixBiome(biome);
            hashA += mixed;
            hashB ^= Long.rotateLeft(mixed, (int) (mixed & 63L));
        }

        int limit = Math.min(this.filledSlots.get(), this.entries.length());
        for (int index = 0; index < limit; index++) {
            Entry entry = this.entries.get(index);
            if (entry == null
                    || entry.biomeCount != biomeCount
                    || entry.hashA != hashA
                    || entry.hashB != hashB
                    || !entry.matches(biomes)) {
                continue;
            }

            scratch.copyCombinedFeatureMasksFrom(entry.masksByStep, entry.nonEmptyStepBits, stepCount, wordCountsByStep);
            DecorationPipelineMetrics.increment(DecorationPipelineMetrics.BIOME_SIGNATURE_CACHE_HITS);
            DecorationPipelineMetrics.add(DecorationPipelineMetrics.BIOME_SIGNATURE_MASK_WORDS_COPIED, entry.maskWordCount);
            DecorationPipelineMetrics.add(
                    DecorationPipelineMetrics.BIOME_SIGNATURE_MASK_WORDS_AVOIDED,
                    (long) biomeCount * entry.maskWordCount
            );
            return true;
        }

        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.BIOME_SIGNATURE_CACHE_MISSES);
        return false;
    }

    public void store(
            ObjectArraySet<Holder<Biome>> biomes,
            BiomeDecorationScratch scratch,
            int stepCount,
            int[] wordCountsByStep
    ) {
        int biomeCount = biomes.size();
        if (biomeCount == 0) {
            return;
        }

        @SuppressWarnings("unchecked")
        Holder<Biome>[] copiedBiomes = (Holder<Biome>[]) new Holder<?>[biomeCount];
        long hashA = 0L;
        long hashB = 0L;
        int biomeIndex = 0;
        for (Holder<Biome> biome : biomes) {
            copiedBiomes[biomeIndex++] = biome;
            long mixed = mixBiome(biome);
            hashA += mixed;
            hashB ^= Long.rotateLeft(mixed, (int) (mixed & 63L));
        }

        long[][] masksByStep = new long[stepCount][];
        int maskWordCount = 0;
        for (int step = 0; step < stepCount; step++) {
            int wordCount = wordCountsByStep[step];
            if (wordCount <= 0) {
                continue;
            }
            long[] source = scratch.featureMaskForStep(step);
            long[] copy = new long[wordCount];
            System.arraycopy(source, 0, copy, 0, Math.min(wordCount, source.length));
            masksByStep[step] = copy;
            maskWordCount += wordCount;
        }

        Entry entry = new Entry(
                copiedBiomes,
                biomeCount,
                hashA,
                hashB,
                masksByStep,
                scratch.combinedNonEmptyStepBits(),
                maskWordCount
        );
        int slot = this.reserveSlot();
        if (slot < 0) {
            return;
        }
        this.entries.set(slot, entry);
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.BIOME_SIGNATURE_CACHE_STORES);
    }

    private int reserveSlot() {
        int length = this.entries.length();
        for (;;) {
            int slot = this.filledSlots.get();
            if (slot >= length) {
                return -1;
            }
            if (this.filledSlots.compareAndSet(slot, slot + 1)) {
                return slot;
            }
        }
    }

    private static long mixBiome(Holder<Biome> biome) {
        int hash = System.identityHashCode(biome);
        hash = 31 * hash + biome.hashCode();
        long value = (long) hash * 0x9E3779B97F4A7C15L;
        value ^= value >>> 33;
        value *= 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 29;
        return value;
    }

    private static final class Entry {
        private final Holder<Biome>[] biomes;
        private final int biomeCount;
        private final long hashA;
        private final long hashB;
        private final long[][] masksByStep;
        private final long nonEmptyStepBits;
        private final int maskWordCount;

        private Entry(
                Holder<Biome>[] biomes,
                int biomeCount,
                long hashA,
                long hashB,
                long[][] masksByStep,
                long nonEmptyStepBits,
                int maskWordCount
        ) {
            this.biomes = biomes;
            this.biomeCount = biomeCount;
            this.hashA = hashA;
            this.hashB = hashB;
            this.masksByStep = masksByStep;
            this.nonEmptyStepBits = nonEmptyStepBits;
            this.maskWordCount = maskWordCount;
        }

        private boolean matches(ObjectArraySet<Holder<Biome>> candidates) {
            for (int i = 0; i < this.biomeCount; i++) {
                if (!candidates.contains(this.biomes[i])) {
                    return false;
                }
            }
            return true;
        }
    }
}
