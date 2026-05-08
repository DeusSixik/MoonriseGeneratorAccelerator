package dev.sixik.generator_accelerator.common.features.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.KelpFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.SeagrassFeature;
import net.minecraft.world.level.levelgen.feature.SeaPickleFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.WaterloggedVegetationPatchFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public final class DecorationPipelineExecutor {
    public void executeFallbacks(DecorationStepPlan stepPlan, ExecutionContext context) {
        int[] featureIndices = stepPlan.fallbackFeatureIndices();
        this.executeSelected(stepPlan, context, featureIndices, featureIndices.length);
    }

    public void executeSelectedMask(
            DecorationStepPlan stepPlan,
            ExecutionContext context,
            long[] selectedFeatureMask,
            int selectedFeatureMaskWords
    ) {
        if (selectedFeatureMask == null || selectedFeatureMaskWords <= 0) {
            return;
        }

        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        if (stepPlan.selectedNeedsDescriptors(selectedFeatureMask, selectedFeatureMaskWords)) {
            this.prepareDescriptors(context, scratch);
        }

        PipelinePlacementContext placementContext = context.placementContext();
        int featureCount = stepPlan.featureCount();
        int limit = Math.min(selectedFeatureMaskWords, selectedFeatureMask.length);
        for (int wordIndex = 0; wordIndex < limit; wordIndex++) {
            long bits = selectedFeatureMask[wordIndex];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                int featureIndex = (wordIndex << 6) + bit;
                if (featureIndex >= featureCount) {
                    break;
                }

                DecorationKernelPlan kernel = stepPlan.kernelForFeatureIndex(featureIndex);
                if (kernel != null) {
                    this.executeKernel(stepPlan.step(), featureIndex, kernel, context, scratch, placementContext);
                }
                bits &= bits - 1L;
            }
        }
    }

    public void executeSelected(DecorationStepPlan stepPlan, ExecutionContext context, int[] selectedFeatureIndices, int selectedCount) {
        this.executeSelected(stepPlan, context, selectedFeatureIndices, selectedCount, null, 0);
    }

    public void executeSelected(
            DecorationStepPlan stepPlan,
            ExecutionContext context,
            int[] selectedFeatureIndices,
            int selectedCount,
            long[] selectedFeatureMask,
            int selectedFeatureMaskWords
    ) {
        int limit = selectedCount;
        if (selectedFeatureIndices.length < limit) {
            limit = selectedFeatureIndices.length;
        }
        if (limit <= 0) {
            return;
        }

        DecorationPipelineScratch scratch = DecorationPipelineScratch.local();
        if (selectedFeatureMask != null
                ? stepPlan.selectedNeedsDescriptors(selectedFeatureMask, selectedFeatureMaskWords)
                : this.selectedNeedsDescriptors(stepPlan, selectedFeatureIndices, limit)) {
            this.prepareDescriptors(context, scratch);
        }
        PipelinePlacementContext placementContext = context.placementContext();
        for (int i = 0; i < limit; i++) {
            int featureIndex = selectedFeatureIndices[i];
            DecorationKernelPlan kernel = stepPlan.kernelForFeatureIndex(featureIndex);
            if (kernel == null) {
                continue;
            }
            this.executeKernel(stepPlan.step(), featureIndex, kernel, context, scratch, placementContext);
        }
    }

    private void executeKernel(
            int step,
            int featureIndex,
            DecorationKernelPlan kernel,
            ExecutionContext context,
            DecorationPipelineScratch scratch,
            PipelinePlacementContext placementContext
    ) {
        context.random.setFeatureSeed(context.decorationSeed, featureIndex, step);
        context.beforeFallback(featureIndex, kernel.fallbackFeature());
        placementContext.set(context.level, context.generator, kernel.fallbackFeatureOptional());

        long start = DecorationPipelineMetrics.startTimer();
        if (kernel.placementProgram() != null) {
            if (kernel.kind().isNativeKernel()) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_KERNELS_EXECUTED);
            } else if (kernel.kind().isPartialNative()) {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_KERNELS_EXECUTED);
            } else {
                DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
            }
            kernel.placementProgram().execute(kernel, context, scratch, placementContext);
            DecorationPipelineMetrics.addElapsed(elapsedCounter(kernel.kind()), start);
            DecorationPipelineMetrics.addKindElapsed(kernel.kind(), start);
            DecorationPipelineMetrics.addFeatureElapsed(kernel.metricsName(), start);
            return;
        }

        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
        DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_FALLBACK_NANOS, start);
        DecorationPipelineMetrics.addKindElapsed(kernel.kind(), start);
        DecorationPipelineMetrics.addFeatureElapsed(kernel.metricsName(), start);
    }

    private static int elapsedCounter(DecorationKernelKind kind) {
        if (kind.isNativeKernel()) {
            return DecorationPipelineMetrics.DECORATION_NATIVE_NANOS;
        }
        if (kind.isPartialNative()) {
            return DecorationPipelineMetrics.DECORATION_PARTIAL_NATIVE_NANOS;
        }
        return DecorationPipelineMetrics.DECORATION_FALLBACK_NANOS;
    }

    private boolean selectedNeedsDescriptors(DecorationStepPlan stepPlan, int[] selectedFeatureIndices, int limit) {
        for (int i = 0; i < limit; i++) {
            DecorationKernelPlan kernel = stepPlan.kernelForFeatureIndex(selectedFeatureIndices[i]);
            if (kernel != null && kernelNeedsDescriptors(kernel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean kernelNeedsDescriptors(DecorationKernelPlan kernel) {
        DecorationKernelKind kind = kernel.kind();
        if (kind == DecorationKernelKind.PARTIAL_NATIVE_DESCRIPTOR_GATED
                || kind == DecorationKernelKind.NATIVE_ORE
                || kind == DecorationKernelKind.NATIVE_SCATTERED_ORE
                || kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SIMPLE
                || kind == DecorationKernelKind.NATIVE_RANDOM_PATCH_SELECTOR
                || kind == DecorationKernelKind.NATIVE_SELECTOR_SIMPLE
                || kind == DecorationKernelKind.NATIVE_SIMPLE_BLOCK
                || kind == DecorationKernelKind.NATIVE_DISK
                || kind == DecorationKernelKind.NATIVE_BLOCK_COLUMN
                || kind == DecorationKernelKind.NATIVE_PLANT_WATER
                || kind == DecorationKernelKind.NATIVE_SPRING
                || kind == DecorationKernelKind.NATIVE_TREE) {
            return true;
        }

        if (kernel.configuredFeature() == null) {
            return false;
        }
        ConfiguredFeature<?, ?> configuredFeature = kernel.configuredFeature().value();
        if (configuredFeature == null) {
            return false;
        }
        Feature<?> feature = configuredFeature.feature();
        return feature instanceof OreFeature
                || feature instanceof ScatteredOreFeature
                || feature instanceof KelpFeature
                || feature instanceof SeagrassFeature
                || feature instanceof SeaPickleFeature
                || feature instanceof WaterloggedVegetationPatchFeature
                || feature instanceof SimpleBlockFeature
                || feature instanceof RandomPatchFeature
                || feature instanceof VegetationPatchFeature;
    }

    private void prepareDescriptors(ExecutionContext context, DecorationPipelineScratch scratch) {
        if (scratch.descriptorsPreparedFor(context.chunk)) {
            return;
        }
        scratch.descriptors.clear();
        ChunkPos center = context.chunk.getPos();
        long start = DecorationPipelineMetrics.startTimer();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkAccess chunk;
                if (dx == 0 && dz == 0) {
                    chunk = context.chunk;
                } else {
                    try {
                        chunk = context.level.getChunk(center.x + dx, center.z + dz);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                }
                scratch.descriptors.buildChunk(chunk);
            }
        }
        scratch.markDescriptorsPrepared(context.chunk);
        DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_DESCRIPTOR_NANOS, start);
    }

    public static final class ExecutionContext {
        private final WorldGenLevel level;
        private final ChunkAccess chunk;
        private final ChunkGenerator generator;
        private final WorldgenRandom random;
        private final BlockPos origin;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final long decorationSeed;
        private final FallbackHook fallbackHook;
        private final PipelinePlacementContext placementContext;

        public ExecutionContext(
                WorldGenLevel level,
                ChunkAccess chunk,
                ChunkGenerator generator,
                WorldgenRandom random,
                BlockPos origin,
                long decorationSeed,
                FallbackHook fallbackHook,
                PipelinePlacementContext placementContext
        ) {
            this.level = level;
            this.chunk = chunk;
            this.generator = generator;
            this.random = random;
            this.origin = origin;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.decorationSeed = decorationSeed;
            this.fallbackHook = fallbackHook;
            this.placementContext = placementContext.set(level, generator, java.util.Optional.empty());
        }

        private void beforeFallback(int featureIndex, PlacedFeature feature) {
            if (this.fallbackHook != null) {
                this.fallbackHook.beforeFallback(featureIndex, feature);
            }
        }

        WorldGenLevel level() {
            return this.level;
        }

        ChunkAccess chunk() {
            return this.chunk;
        }

        ChunkGenerator generator() {
            return this.generator;
        }

        WorldgenRandom random() {
            return this.random;
        }

        BlockPos origin() {
            return this.origin;
        }

        int originX() {
            return this.originX;
        }

        int originY() {
            return this.originY;
        }

        int originZ() {
            return this.originZ;
        }

        PipelinePlacementContext placementContext() {
            return this.placementContext;
        }
    }

    @FunctionalInterface
    public interface FallbackHook {
        void beforeFallback(int featureIndex, PlacedFeature feature);
    }
}
