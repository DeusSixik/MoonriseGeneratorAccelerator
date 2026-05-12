package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
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
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

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
        placementContext.set(context.level, context.generator, kernel.fallbackFeatureOptional(), activeDescriptors(context, scratch), context.workspace);

        long start = DecorationPipelineMetrics.startTimer();
        long workspaceStart = context.workspace == null ? 0L : System.nanoTime();
        int elapsedCounter = elapsedCounter(kernel.kind());
        try {
            if (DecorationPipelineCompatibility.shouldUseSafeVanilla(kernel.fallbackFeature())) {
                elapsedCounter = DecorationPipelineMetrics.DECORATION_FALLBACK_NANOS;
                this.executeSafeVanilla(kernel, context, placementContext, scratch);
                return;
            }

            if (kernel.placementProgram() != null) {
                if (kernel.selectorFallbackMetricCounter() >= 0) {
                    DecorationPipelineMetrics.increment(kernel.selectorFallbackMetricCounter());
                }
                if (kernel.kind().isNativeKernel()) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.NATIVE_KERNELS_EXECUTED);
                } else if (kernel.kind().isPartialNative()) {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.PARTIAL_NATIVE_KERNELS_EXECUTED);
                } else {
                    DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
                }
                kernel.placementProgram().execute(kernel, context, scratch, placementContext);
                return;
            }

            elapsedCounter = DecorationPipelineMetrics.DECORATION_FALLBACK_NANOS;
            this.executeSafeVanilla(kernel, context, placementContext, scratch);
        } catch (RuntimeException failure) {
            DecorationPipelineCompatibility.quarantine(
                    context.placedFeatureRegistry(),
                    kernel.fallbackFeature(),
                    kernel,
                    step,
                    featureIndex,
                    failure
            );
            context.random.setFeatureSeed(context.decorationSeed, featureIndex, step);
            placementContext.set(context.level, context.generator, kernel.fallbackFeatureOptional(), activeDescriptors(context, scratch), context.workspace);
            try {
                elapsedCounter = DecorationPipelineMetrics.DECORATION_FALLBACK_NANOS;
                this.executeSafeVanilla(kernel, context, placementContext, scratch);
                return;
            } catch (RuntimeException fallbackFailure) {
                failure.addSuppressed(fallbackFailure);
                throw failure;
            }
        } finally {
            if (context.workspace != null) {
                context.workspace.metrics().addComputeNanos(System.nanoTime() - workspaceStart);
            }
            DecorationPipelineMetrics.addElapsed(elapsedCounter, start);
            DecorationPipelineMetrics.addKindElapsed(kernel.kind(), start);
            DecorationPipelineMetrics.addFeatureElapsed(kernel.metricsName(), start);
        }
    }

    private void executeSafeVanilla(
            DecorationKernelPlan kernel,
            ExecutionContext context,
            PipelinePlacementContext placementContext,
            DecorationPipelineScratch scratch
    ) {
        DecorationPipelineMetrics.increment(DecorationPipelineMetrics.FALLBACK_VANILLA_CALLS);
        executeVanillaPlacedFeature(kernel.fallbackFeature(), placementContext, context.random(), context.origin(), scratch);
    }

    private static boolean executeVanillaPlacedFeature(
            PlacedFeature feature,
            PipelinePlacementContext context,
            WorldgenRandom random,
            BlockPos startPos,
            DecorationPipelineScratch scratch
    ) {
        if (feature == null) {
            return false;
        }
        return executeVanillaPlacedFeature(feature, context, random, startPos, 0, scratch);
    }

    private static boolean executeVanillaPlacedFeature(
            PlacedFeature feature,
            PipelinePlacementContext context,
            WorldgenRandom random,
            BlockPos pos,
            int modifierIndex,
            DecorationPipelineScratch scratch
    ) {
        List<PlacementModifier> placement = feature.placement();
        if (modifierIndex >= placement.size()) {
            return feature.feature().value().place(context.getLevel(), context.generator(), random, pos);
        }

        PlacementModifier modifier = placement.get(modifierIndex);
        if (modifier instanceof GA$PlacementModifierExtension extension && extension.ga$hasFastPositions()) {
            boolean success = false;
            LongScratchBuffer positions = scratch.acquireModifierPositionBuffer();
            try {
                extension.generatePositionsRaw(context, random, pos.asLong(), positions);
                long[] values = positions.elements();
                for (int i = 0, size = positions.size(); i < size; i++) {
                    long packedPos = values[i];
                    BlockPos.MutableBlockPos nextPos = scratch.modifierMutablePos(modifierIndex).set(packedPos);
                    if (executeVanillaPlacedFeature(feature, context, random, nextPos, modifierIndex + 1, scratch)) {
                        success = true;
                    }
                }
            } finally {
                scratch.releaseModifierPositionBuffer();
            }
            return success;
        }

        boolean success = false;
        try (Stream<BlockPos> positions = modifier.getPositions(context, random, pos)) {
            Iterator<BlockPos> iterator = positions.iterator();
            while (iterator.hasNext()) {
                if (executeVanillaPlacedFeature(feature, context, random, iterator.next(), modifierIndex + 1, scratch)) {
                    success = true;
                }
            }
        }
        return success;
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
        long start = DecorationPipelineMetrics.startTimer();
        long workspaceStart = context.workspace == null ? 0L : System.nanoTime();
        try {
            scratch.descriptors.prepareChunkLazy(context.chunk);
            scratch.markDescriptorsPrepared(context.chunk);
        } catch (RuntimeException failure) {
            scratch.descriptors.clear();
            if (DecorationPipelineCompatibility.shouldLogDescriptorFailure(failure)) {
                GeneratorAccelerator.LOGGER.warn(
                        "GA decoration pipeline failed to prepare descriptor cache for chunk [{}, {}]; descriptor-gated kernels will fall back to live reads for this pass.",
                        context.chunkX(),
                        context.chunkZ(),
                        failure
                );
            }
        } finally {
            if (context.workspace != null) {
                context.workspace.metrics().addImportNanos(System.nanoTime() - workspaceStart);
            }
            DecorationPipelineMetrics.addElapsed(DecorationPipelineMetrics.DECORATION_DESCRIPTOR_NANOS, start);
        }
    }

    public static final class ExecutionContext {
        private final WorldGenLevel level;
        private final ChunkAccess chunk;
        private final int chunkX;
        private final int chunkZ;
        private final ChunkGenerator generator;
        private final WorldgenRandom random;
        private final BlockPos origin;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final long decorationSeed;
        private final Registry<PlacedFeature> placedFeatureRegistry;
        private final FallbackHook fallbackHook;
        private final PipelinePlacementContext placementContext;
        private final GAChunkWorkspace workspace;

        public ExecutionContext(
                WorldGenLevel level,
                ChunkAccess chunk,
                ChunkGenerator generator,
                WorldgenRandom random,
                BlockPos origin,
                long decorationSeed,
                Registry<PlacedFeature> placedFeatureRegistry,
                FallbackHook fallbackHook,
                PipelinePlacementContext placementContext
        ) {
            this.level = level;
            this.chunk = chunk;
            ChunkPos chunkPos = chunk.getPos();
            this.chunkX = chunkPos.x;
            this.chunkZ = chunkPos.z;
            this.generator = generator;
            this.random = random;
            this.origin = origin;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();
            this.decorationSeed = decorationSeed;
            this.placedFeatureRegistry = placedFeatureRegistry;
            this.fallbackHook = fallbackHook;
            this.workspace = GAChunkWorkspaceContext.current();
            this.placementContext = placementContext.set(level, generator, java.util.Optional.empty(), null, this.workspace);
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

        int chunkX() {
            return this.chunkX;
        }

        int chunkZ() {
            return this.chunkZ;
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

        Registry<PlacedFeature> placedFeatureRegistry() {
            return this.placedFeatureRegistry;
        }

        PipelinePlacementContext placementContext() {
            return this.placementContext;
        }

        public GAChunkWorkspace workspace() {
            return this.workspace;
        }
    }

    private static SectionDescriptorCache activeDescriptors(ExecutionContext context, DecorationPipelineScratch scratch) {
        return scratch.descriptorsPreparedFor(context.chunk) ? scratch.descriptors : null;
    }

    @FunctionalInterface
    public interface FallbackHook {
        void beforeFallback(int featureIndex, PlacedFeature feature);
    }
}
