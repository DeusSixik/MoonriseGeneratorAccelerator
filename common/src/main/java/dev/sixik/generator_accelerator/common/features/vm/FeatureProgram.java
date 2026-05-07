package dev.sixik.generator_accelerator.common.features.vm;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$RepeatingPlacementAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.List;

public final class FeatureProgram {
    private final int[] opcodes;
    private final PlacementModifier[] modifiers;
    private final Holder<ConfiguredFeature<?, ?>> feature;
    private final ConfiguredFeature<?, ?> configuredFeature;
    private final Feature<FeatureConfiguration> featureImpl;
    private final FeatureConfiguration featureConfig;
    private final int fastOpCount;
    private final int fallbackOpCount;
    private final boolean linearFastOnly;
    private final int specializedExecutor;
    private final HeightProvider[] heightProviders;
    private final Heightmap.Types[] heightmapTypes;
    private final RandomOffsetData[] randomOffsets;
    private final GA$RepeatingPlacementAccess[] repeatingPlacements;
    private final GA$PlacementFilterAccess[] placementFilters;
    private final FixedPlacementData[] fixedPlacements;
    private final GenerationStep.Carving[] carvingSteps;
    private final EnvironmentScanData[] environmentScans;
    private final IntProvider[] countProviders;
    private final GA$PlacementModifierExtension[] rawModifiers;

    @SuppressWarnings("unchecked")
    FeatureProgram(
            int[] opcodes,
            PlacementModifier[] modifiers,
            Holder<ConfiguredFeature<?, ?>> feature,
            int fastOpCount,
            int fallbackOpCount,
            boolean linearFastOnly,
            int specializedExecutor,
            HeightProvider[] heightProviders,
            Heightmap.Types[] heightmapTypes,
            RandomOffsetData[] randomOffsets,
            GA$RepeatingPlacementAccess[] repeatingPlacements,
            GA$PlacementFilterAccess[] placementFilters,
            FixedPlacementData[] fixedPlacements,
            GenerationStep.Carving[] carvingSteps,
            EnvironmentScanData[] environmentScans,
            IntProvider[] countProviders,
            GA$PlacementModifierExtension[] rawModifiers
    ) {
        this.opcodes = opcodes;
        this.modifiers = modifiers;
        this.feature = feature;
        this.configuredFeature = feature.value();
        this.featureImpl = (Feature<FeatureConfiguration>) this.configuredFeature.feature();
        this.featureConfig = this.configuredFeature.config();
        this.fastOpCount = fastOpCount;
        this.fallbackOpCount = fallbackOpCount;
        this.linearFastOnly = linearFastOnly;
        this.specializedExecutor = specializedExecutor;
        this.heightProviders = heightProviders;
        this.heightmapTypes = heightmapTypes;
        this.randomOffsets = randomOffsets;
        this.repeatingPlacements = repeatingPlacements;
        this.placementFilters = placementFilters;
        this.fixedPlacements = fixedPlacements;
        this.carvingSteps = carvingSteps;
        this.environmentScans = environmentScans;
        this.countProviders = countProviders;
        this.rawModifiers = rawModifiers;
    }

    int[] opcodes() {
        return this.opcodes;
    }

    PlacementModifier[] modifiers() {
        return this.modifiers;
    }

    public int opCount() {
        return this.opcodes.length;
    }

    public int opcode(int index) {
        return this.opcodes[index];
    }

    public PlacementModifier modifier(int index) {
        return this.modifiers[index];
    }

    public Holder<ConfiguredFeature<?, ?>> feature() {
        return this.feature;
    }

    public ConfiguredFeature<?, ?> configuredFeature() {
        return this.configuredFeature;
    }

    public Feature<FeatureConfiguration> featureImpl() {
        return this.featureImpl;
    }

    public FeatureConfiguration featureConfig() {
        return this.featureConfig;
    }

    public boolean hasFallback() {
        return this.fallbackOpCount != 0;
    }

    public int fastOpCount() {
        return this.fastOpCount;
    }

    public int fallbackOpCount() {
        return this.fallbackOpCount;
    }

    public boolean linearFastOnly() {
        return this.linearFastOnly;
    }

    public int specializedExecutor() {
        return this.specializedExecutor;
    }

    public HeightProvider heightProvider(int index) {
        return this.heightProviders[index];
    }

    public Heightmap.Types heightmapType(int index) {
        return this.heightmapTypes[index];
    }

    public RandomOffsetData randomOffset(int index) {
        return this.randomOffsets[index];
    }

    public GA$RepeatingPlacementAccess repeatingPlacement(int index) {
        return this.repeatingPlacements[index];
    }

    public GA$PlacementFilterAccess placementFilter(int index) {
        return this.placementFilters[index];
    }

    public FixedPlacementData fixedPlacement(int index) {
        return this.fixedPlacements[index];
    }

    public GenerationStep.Carving carvingStep(int index) {
        return this.carvingSteps[index];
    }

    public EnvironmentScanData environmentScan(int index) {
        return this.environmentScans[index];
    }

    public IntProvider countProvider(int index) {
        return this.countProviders[index];
    }

    public GA$PlacementModifierExtension rawModifier(int index) {
        return this.rawModifiers[index];
    }

    public static final class RandomOffsetData {
        private final IntProvider xzSpread;
        private final IntProvider ySpread;

        RandomOffsetData(IntProvider xzSpread, IntProvider ySpread) {
            this.xzSpread = xzSpread;
            this.ySpread = ySpread;
        }

        public IntProvider xzSpread() {
            return this.xzSpread;
        }

        public IntProvider ySpread() {
            return this.ySpread;
        }
    }

    public static final class FixedPlacementData {
        private final long[] positions;
        private final int[] chunkXs;
        private final int[] chunkZs;

        FixedPlacementData(List<BlockPos> positions) {
            int size = positions.size();
            this.positions = new long[size];
            this.chunkXs = new int[size];
            this.chunkZs = new int[size];
            for (int i = 0; i < size; i++) {
                BlockPos pos = positions.get(i);
                this.positions[i] = pos.asLong();
                this.chunkXs[i] = pos.getX() >> 4;
                this.chunkZs[i] = pos.getZ() >> 4;
            }
        }

        public long[] positions() {
            return this.positions;
        }

        public int[] chunkXs() {
            return this.chunkXs;
        }

        public int[] chunkZs() {
            return this.chunkZs;
        }
    }

    public static final class EnvironmentScanData {
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final int maxSteps;

        EnvironmentScanData(int stepX, int stepY, int stepZ, int maxSteps) {
            this.stepX = stepX;
            this.stepY = stepY;
            this.stepZ = stepZ;
            this.maxSteps = maxSteps;
        }

        public int stepX() {
            return this.stepX;
        }

        public int stepY() {
            return this.stepY;
        }

        public int stepZ() {
            return this.stepZ;
        }

        public int maxSteps() {
            return this.maxSteps;
        }
    }
}
