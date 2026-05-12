package dev.sixik.generator_accelerator.common.features.pipeline;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead counters for the data-oriented decoration pipeline. */
public final class DecorationPipelineMetrics {

    public static volatile boolean ENABLED = Boolean.getBoolean("ga.decorationPipeline.metrics");

    public static final int DECORATION_TOTAL_NANOS = 0;
    public static final int DECORATION_NATIVE_NANOS = 1;
    public static final int DECORATION_PARTIAL_NATIVE_NANOS = 2;
    public static final int DECORATION_FALLBACK_NANOS = 3;
    public static final int DECORATION_DESCRIPTOR_NANOS = 4;
    public static final int DECORATION_CANDIDATE_NANOS = 5;
    public static final int DECORATION_COMMIT_NANOS = 6;
    public static final int NATIVE_KERNELS_COMPILED = 7;
    public static final int NATIVE_KERNELS_EXECUTED = 8;
    public static final int PARTIAL_NATIVE_KERNELS_COMPILED = 9;
    public static final int PARTIAL_NATIVE_KERNELS_EXECUTED = 10;
    public static final int PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS = 11;
    public static final int PARTIAL_NATIVE_OPTIMIZED_PLACEMENT_CALLS = 12;
    public static final int NATIVE_CANDIDATES_GENERATED = 13;
    public static final int NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR = 14;
    public static final int NATIVE_CANDIDATES_REJECTED_BY_KERNEL = 15;
    public static final int NATIVE_SECTION_BATCHES = 16;
    public static final int FALLBACK_LEGACY_VM_CALLS = 17;
    public static final int FALLBACK_VANILLA_CALLS = 18;
    public static final int WORLD_SECTION_SWITCHES = 19;
    public static final int WORLD_BLOCK_READS = 20;
    public static final int WORLD_BLOCK_WRITES = 21;
    public static final int ALLOC_RUNTIME_OBJECTS = 22;
    public static final int ALLOC_BUFFER_GROWTHS = 23;
    public static final int ALLOC_FALLBACK_CONTEXT_OBJECTS = 24;
    public static final int SLOW_PATH_OBJECT_ALLOCATING_CALLS = 25;
    public static final int SLOW_PATH_GENERIC_COLLECTION_CALLS = 26;
    public static final int DESCRIPTOR_SECTION_REJECTS = 27;
    public static final int DESCRIPTOR_COLUMN_REJECTS = 28;
    public static final int DESCRIPTOR_HEIGHTMAP_HITS = 29;
    public static final int DESCRIPTOR_WORLD_READS_AVOIDED = 30;
    public static final int SELECTOR_UNSUPPORTED_PLACEMENT_MODIFIER = 31;
    public static final int SELECTOR_UNSUPPORTED_BIOME_FILTER = 32;
    public static final int SELECTOR_UNSUPPORTED_DEPTH_CAP = 33;
    public static final int SELECTOR_UNSUPPORTED_BRANCH_FAMILY = 34;
    public static final int SELECTOR_UNSUPPORTED_CONFIG = 35;
    public static final int JOURNAL_WRITE_CANDIDATES = 36;
    public static final int JOURNAL_WRITES_COMMITTED = 37;
    public static final int JOURNAL_COLLISIONS = 38;
    public static final int JOURNAL_DEDUPED_WRITES = 39;
    public static final int JOURNAL_TOUCHED_SECTION_COLUMNS = 40;
    public static final int JOURNAL_COMMIT_BATCHES = 41;
    public static final int OUTER_BIOME_SCAN_NANOS = 42;
    public static final int OUTER_MASK_COMBINE_NANOS = 43;
    public static final int BIOME_SIGNATURE_CACHE_HITS = 44;
    public static final int BIOME_SIGNATURE_CACHE_MISSES = 45;
    public static final int BIOME_SIGNATURE_CACHE_STORES = 46;
    public static final int BIOME_SIGNATURE_MASK_WORDS_COPIED = 47;
    public static final int BIOME_SIGNATURE_MASK_WORDS_AVOIDED = 48;
    public static final int PLACEMENT_PROGRAM_CACHE_HITS = 49;
    public static final int PLACEMENT_PROGRAM_CACHE_MISSES = 50;
    public static final int PLACEMENT_PROGRAM_CACHE_STORES = 51;
    public static final int DESCRIPTOR_SIMPLE_BLOCK_MICRO_REJECTS = 52;
    public static final int SELECTOR_FUSED_SIMPLE_CALLS = 53;
    public static final int SELECTOR_FUSED_PLACEMENT_CALLS = 54;
    public static final int SELECTOR_FUSED_FAST_SIMPLE_CALLS = 55;
    public static final int SELECTOR_FUSED_FAST_RANDOM_PATCH_SIMPLE_CALLS = 56;
    public static final int SELECTOR_FUSED_FAST_RANDOM_PATCH_SELECTOR_CALLS = 57;
    public static final int SELECTOR_FUSED_FAST_SELECTOR_CALLS = 58;
    public static final int SELECTOR_FUSED_GENERIC_CALLS = 59;
    public static final int SELECTOR_FUSED_DESCRIPTOR_REJECTS = 60;
    public static final int CLASSIFIER_TIER0_UNITS = 61;
    public static final int CLASSIFIER_TIER1_UNITS = 62;
    public static final int CLASSIFIER_TIER2_UNITS = 63;
    public static final int CLASSIFIER_TIER3_UNITS = 64;
    public static final int CLASSIFIER_TIER4_UNITS = 65;
    public static final int CLASSIFIER_TIER5_UNITS = 66;
    public static final int CLASSIFIER_NATIVE_FEATURES = 67;
    public static final int CLASSIFIER_PARTIAL_FEATURES = 68;
    public static final int CLASSIFIER_UNKNOWN_FEATURES = 69;
    public static final int CLASSIFIER_DESCRIPTOR_GATED = 70;
    public static final int WORKSPACE_BLOCK_MIRRORS = 71;
    public static final int WORKSPACE_BLOCK_MIRROR_SKIPS = 72;
    public static final int WORKSPACE_BLOCK_READ_HITS = 73;
    public static final int WORKSPACE_DESCRIPTOR_REPAIRS = 74;

    public static final int COUNTER_COUNT = 75;

    private static final String[] NAMES = {
            "decoration.totalNanos",
            "decoration.nativeNanos",
            "decoration.partialNativeNanos",
            "decoration.fallbackNanos",
            "decoration.descriptorNanos",
            "decoration.candidateNanos",
            "decoration.commitNanos",
            "native.kernelsCompiled",
            "native.kernelsExecuted",
            "partialNative.kernelsCompiled",
            "partialNative.kernelsExecuted",
            "partialNative.descriptorRejectedCalls",
            "partialNative.optimizedPlacementCalls",
            "native.candidatesGenerated",
            "native.candidatesRejectedByDescriptor",
            "native.candidatesRejectedByKernel",
            "native.sectionBatches",
            "fallback.legacyVmCalls",
            "fallback.vanillaCalls",
            "world.sectionSwitches",
            "world.blockReads",
            "world.blockWrites",
            "alloc.runtimeObjects",
            "alloc.bufferGrowths",
            "alloc.fallbackContextObjects",
            "slowPath.objectAllocatingCalls",
            "slowPath.genericCollectionCalls",
            "descriptor.sectionRejects",
            "descriptor.columnRejects",
            "descriptor.heightmapHits",
            "descriptor.worldReadsAvoided",
            "selector.unsupportedPlacementModifier",
            "selector.unsupportedBiomeFilter",
            "selector.unsupportedDepthCap",
            "selector.unsupportedBranchFamily",
            "selector.unsupportedConfig",
            "journal.writeCandidates",
            "journal.writesCommitted",
            "journal.collisions",
            "journal.dedupedWrites",
            "journal.touchedSectionColumns",
            "journal.commitBatches",
            "outer.biomeScanNanos",
            "outer.maskCombineNanos",
            "biomeSignature.cacheHits",
            "biomeSignature.cacheMisses",
            "biomeSignature.cacheStores",
            "biomeSignature.maskWordsCopied",
            "biomeSignature.maskWordsAvoided",
            "placementProgram.cacheHits",
            "placementProgram.cacheMisses",
            "placementProgram.cacheStores",
            "descriptor.simpleBlockMicroRejects",
            "selector.fusedSimpleCalls",
            "selector.fusedPlacementCalls",
            "selector.fusedFastSimpleCalls",
            "selector.fusedFastRandomPatchSimpleCalls",
            "selector.fusedFastRandomPatchSelectorCalls",
            "selector.fusedFastSelectorCalls",
            "selector.fusedGenericCalls",
            "selector.fusedDescriptorRejects",
            "classifier.tier0Units",
            "classifier.tier1Units",
            "classifier.tier2Units",
            "classifier.tier3Units",
            "classifier.tier4Units",
            "classifier.tier5Units",
            "classifier.nativeFeatures",
            "classifier.partialFeatures",
            "classifier.unknownFeatures",
            "classifier.descriptorGated",
            "workspace.blockMirrors",
            "workspace.blockMirrorSkips",
            "workspace.blockReadHits",
            "workspace.descriptorRepairs"
    };

    private static final AtomicLongArray COUNTERS = new AtomicLongArray(COUNTER_COUNT);
    private static final AtomicLongArray KIND_EXECUTIONS = new AtomicLongArray(DecorationKernelKind.values().length);
    private static final AtomicLongArray KIND_NANOS = new AtomicLongArray(DecorationKernelKind.values().length);
    private static final ConcurrentHashMap<String, FeatureMetric> FEATURE_METRICS = new ConcurrentHashMap<>();

    private DecorationPipelineMetrics() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void addElapsed(int counter, long startNanos) {
        if (ENABLED) {
            COUNTERS.addAndGet(counter, System.nanoTime() - startNanos);
        }
    }

    public static void addKindElapsed(DecorationKernelKind kind, long startNanos) {
        if (ENABLED) {
            int index = kind.ordinal();
            KIND_EXECUTIONS.incrementAndGet(index);
            KIND_NANOS.addAndGet(index, System.nanoTime() - startNanos);
        }
    }

    public static void addFeatureElapsed(String featureName, long startNanos) {
        if (ENABLED) {
            FeatureMetric metric = FEATURE_METRICS.computeIfAbsent(featureName, ignored -> new FeatureMetric());
            metric.count.incrementAndGet();
            metric.nanos.addAndGet(System.nanoTime() - startNanos);
        }
    }

    public static void increment(int counter) {
        if (ENABLED) {
            COUNTERS.incrementAndGet(counter);
        }
    }

    public static void add(int counter, long amount) {
        if (ENABLED && amount != 0L) {
            COUNTERS.addAndGet(counter, amount);
        }
    }

    public static long get(int counter) {
        return ENABLED ? COUNTERS.get(counter) : 0L;
    }

    public static void copyTo(long[] out) {
        int limit = out.length < COUNTER_COUNT ? out.length : COUNTER_COUNT;
        for (int i = 0; i < limit; i++) {
            out[i] = ENABLED ? COUNTERS.get(i) : 0L;
        }
    }

    public static void reset() {
        for (int i = 0; i < COUNTER_COUNT; i++) {
            COUNTERS.set(i, 0L);
        }
        for (int i = 0; i < DecorationKernelKind.values().length; i++) {
            KIND_EXECUTIONS.set(i, 0L);
            KIND_NANOS.set(i, 0L);
        }
        FEATURE_METRICS.clear();
    }

    public static String name(int counter) {
        return NAMES[counter];
    }

    public static double successfulWritesPerWorldRead() {
        if (!ENABLED) {
            return 0.0D;
        }
        long reads = COUNTERS.get(WORLD_BLOCK_READS);
        if (reads == 0L) {
            return 0.0D;
        }
        return (double) COUNTERS.get(WORLD_BLOCK_WRITES) / (double) reads;
    }

    public static String summary() {
        return "DecorationPipeline metrics: totalDecorationMs=" + millis(DECORATION_TOTAL_NANOS)
                + ", nativeMs=" + millis(DECORATION_NATIVE_NANOS)
                + ", partialNativeMs=" + millis(DECORATION_PARTIAL_NATIVE_NANOS)
                + ", fallbackMs=" + millis(DECORATION_FALLBACK_NANOS)
                + ", descriptorMs=" + millis(DECORATION_DESCRIPTOR_NANOS)
                + ", candidateMs=" + millis(DECORATION_CANDIDATE_NANOS)
                + ", commitMs=" + millis(DECORATION_COMMIT_NANOS)
                + ", nativeKernelsCompiled=" + value(NATIVE_KERNELS_COMPILED)
                + ", nativeKernelsExecuted=" + value(NATIVE_KERNELS_EXECUTED)
                + ", partialNativeKernelsCompiled=" + value(PARTIAL_NATIVE_KERNELS_COMPILED)
                + ", partialNativeKernelsExecuted=" + value(PARTIAL_NATIVE_KERNELS_EXECUTED)
                + ", partialNativeDescriptorRejectedCalls=" + value(PARTIAL_NATIVE_DESCRIPTOR_REJECTED_CALLS)
                + ", partialNativeOptimizedPlacementCalls=" + value(PARTIAL_NATIVE_OPTIMIZED_PLACEMENT_CALLS)
                + ", nativeCandidatesGenerated=" + value(NATIVE_CANDIDATES_GENERATED)
                + ", nativeCandidatesRejectedByDescriptor=" + value(NATIVE_CANDIDATES_REJECTED_BY_DESCRIPTOR)
                + ", nativeCandidatesRejectedByKernel=" + value(NATIVE_CANDIDATES_REJECTED_BY_KERNEL)
                + ", nativeSectionBatches=" + value(NATIVE_SECTION_BATCHES)
                + ", fallbackLegacyVmCalls=" + value(FALLBACK_LEGACY_VM_CALLS)
                + ", fallbackVanillaCalls=" + value(FALLBACK_VANILLA_CALLS)
                + ", worldSectionSwitches=" + value(WORLD_SECTION_SWITCHES)
                + ", worldBlockReads=" + value(WORLD_BLOCK_READS)
                + ", worldBlockWrites=" + value(WORLD_BLOCK_WRITES)
                + ", successfulWritesPerWorldRead=" + successfulWritesPerWorldRead()
                + ", allocRuntimeObjects=" + value(ALLOC_RUNTIME_OBJECTS)
                + ", allocBufferGrowths=" + value(ALLOC_BUFFER_GROWTHS)
                + ", allocFallbackContextObjects=" + value(ALLOC_FALLBACK_CONTEXT_OBJECTS)
                + ", slowPathObjectAllocatingCalls=" + value(SLOW_PATH_OBJECT_ALLOCATING_CALLS)
                + ", slowPathGenericCollectionCalls=" + value(SLOW_PATH_GENERIC_COLLECTION_CALLS)
                + ", descriptorSectionRejects=" + value(DESCRIPTOR_SECTION_REJECTS)
                + ", descriptorColumnRejects=" + value(DESCRIPTOR_COLUMN_REJECTS)
                + ", descriptorHeightmapHits=" + value(DESCRIPTOR_HEIGHTMAP_HITS)
                + ", descriptorWorldReadsAvoided=" + value(DESCRIPTOR_WORLD_READS_AVOIDED)
                + ", selectorUnsupportedPlacementModifier=" + value(SELECTOR_UNSUPPORTED_PLACEMENT_MODIFIER)
                + ", selectorUnsupportedBiomeFilter=" + value(SELECTOR_UNSUPPORTED_BIOME_FILTER)
                + ", selectorUnsupportedDepthCap=" + value(SELECTOR_UNSUPPORTED_DEPTH_CAP)
                + ", selectorUnsupportedBranchFamily=" + value(SELECTOR_UNSUPPORTED_BRANCH_FAMILY)
                + ", selectorUnsupportedConfig=" + value(SELECTOR_UNSUPPORTED_CONFIG)
                + ", journalWriteCandidates=" + value(JOURNAL_WRITE_CANDIDATES)
                + ", journalWritesCommitted=" + value(JOURNAL_WRITES_COMMITTED)
                + ", journalCollisions=" + value(JOURNAL_COLLISIONS)
                + ", journalDedupedWrites=" + value(JOURNAL_DEDUPED_WRITES)
                + ", journalTouchedSectionColumns=" + value(JOURNAL_TOUCHED_SECTION_COLUMNS)
                + ", journalCommitBatches=" + value(JOURNAL_COMMIT_BATCHES)
                + ", outerBiomeScanMs=" + millis(OUTER_BIOME_SCAN_NANOS)
                + ", outerMaskCombineMs=" + millis(OUTER_MASK_COMBINE_NANOS)
                + ", biomeSignatureCacheHits=" + value(BIOME_SIGNATURE_CACHE_HITS)
                + ", biomeSignatureCacheMisses=" + value(BIOME_SIGNATURE_CACHE_MISSES)
                + ", biomeSignatureCacheStores=" + value(BIOME_SIGNATURE_CACHE_STORES)
                + ", biomeSignatureMaskWordsCopied=" + value(BIOME_SIGNATURE_MASK_WORDS_COPIED)
                + ", biomeSignatureMaskWordsAvoided=" + value(BIOME_SIGNATURE_MASK_WORDS_AVOIDED)
                + ", placementProgramCacheHits=" + value(PLACEMENT_PROGRAM_CACHE_HITS)
                + ", placementProgramCacheMisses=" + value(PLACEMENT_PROGRAM_CACHE_MISSES)
                + ", placementProgramCacheStores=" + value(PLACEMENT_PROGRAM_CACHE_STORES)
                + ", descriptorSimpleBlockMicroRejects=" + value(DESCRIPTOR_SIMPLE_BLOCK_MICRO_REJECTS)
                + ", selectorFusedSimpleCalls=" + value(SELECTOR_FUSED_SIMPLE_CALLS)
                + ", selectorFusedPlacementCalls=" + value(SELECTOR_FUSED_PLACEMENT_CALLS)
                + ", selectorFusedFastSimpleCalls=" + value(SELECTOR_FUSED_FAST_SIMPLE_CALLS)
                + ", selectorFusedFastRandomPatchSimpleCalls=" + value(SELECTOR_FUSED_FAST_RANDOM_PATCH_SIMPLE_CALLS)
                + ", selectorFusedFastRandomPatchSelectorCalls=" + value(SELECTOR_FUSED_FAST_RANDOM_PATCH_SELECTOR_CALLS)
                + ", selectorFusedFastSelectorCalls=" + value(SELECTOR_FUSED_FAST_SELECTOR_CALLS)
                + ", selectorFusedGenericCalls=" + value(SELECTOR_FUSED_GENERIC_CALLS)
                + ", selectorFusedDescriptorRejects=" + value(SELECTOR_FUSED_DESCRIPTOR_REJECTS)
                + ", classifierTier0Units=" + value(CLASSIFIER_TIER0_UNITS)
                + ", classifierTier1Units=" + value(CLASSIFIER_TIER1_UNITS)
                + ", classifierTier2Units=" + value(CLASSIFIER_TIER2_UNITS)
                + ", classifierTier3Units=" + value(CLASSIFIER_TIER3_UNITS)
                + ", classifierTier4Units=" + value(CLASSIFIER_TIER4_UNITS)
                + ", classifierTier5Units=" + value(CLASSIFIER_TIER5_UNITS)
                + ", classifierNativeFeatures=" + value(CLASSIFIER_NATIVE_FEATURES)
                + ", classifierPartialFeatures=" + value(CLASSIFIER_PARTIAL_FEATURES)
                + ", classifierUnknownFeatures=" + value(CLASSIFIER_UNKNOWN_FEATURES)
                + ", classifierDescriptorGated=" + value(CLASSIFIER_DESCRIPTOR_GATED)
                + ", workspaceBlockMirrors=" + value(WORKSPACE_BLOCK_MIRRORS)
                + ", workspaceBlockMirrorSkips=" + value(WORKSPACE_BLOCK_MIRROR_SKIPS)
                + ", workspaceBlockReadHits=" + value(WORKSPACE_BLOCK_READ_HITS)
                + ", workspaceDescriptorRepairs=" + value(WORKSPACE_DESCRIPTOR_REPAIRS)
                + ", kindBreakdown=" + kindBreakdown()
                + ", featureBreakdown=" + featureBreakdown();
    }

    private static long millis(int counter) {
        return value(counter) / 1_000_000L;
    }

    private static long value(int counter) {
        return ENABLED ? COUNTERS.get(counter) : 0L;
    }

    private static String kindBreakdown() {
        if (!ENABLED) {
            return "";
        }
        StringBuilder builder = new StringBuilder(256);
        DecorationKernelKind[] kinds = DecorationKernelKind.values();
        boolean first = true;
        for (int i = 0; i < kinds.length; i++) {
            long executions = KIND_EXECUTIONS.get(i);
            if (executions == 0L) {
                continue;
            }
            if (!first) {
                builder.append(';');
            }
            first = false;
            builder.append(kinds[i].name())
                    .append(":count=").append(executions)
                    .append(",ms=").append(KIND_NANOS.get(i) / 1_000_000L);
        }
        return builder.toString();
    }

    private static String featureBreakdown() {
        if (!ENABLED || FEATURE_METRICS.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(512);
        FeatureMetric[] top = new FeatureMetric[8];
        String[] names = new String[8];
        FEATURE_METRICS.forEach((name, metric) -> {
            long nanos = metric.nanos.get();
            for (int i = 0; i < top.length; i++) {
                FeatureMetric current = top[i];
                if (current == null || nanos > current.nanos.get()) {
                    for (int j = top.length - 1; j > i; j--) {
                        top[j] = top[j - 1];
                        names[j] = names[j - 1];
                    }
                    top[i] = metric;
                    names[i] = name;
                    break;
                }
            }
        });
        for (int i = 0; i < top.length && top[i] != null; i++) {
            if (i > 0) {
                builder.append(';');
            }
            builder.append(names[i])
                    .append(":count=").append(top[i].count.get())
                    .append(",ms=").append(top[i].nanos.get() / 1_000_000L);
        }
        return builder.toString();
    }

    private static final class FeatureMetric {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();
    }
}
