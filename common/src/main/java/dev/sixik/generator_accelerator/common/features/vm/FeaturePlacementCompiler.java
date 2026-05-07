package dev.sixik.generator_accelerator.common.features.vm;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.api.patches.GA$CarvingMaskPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$CountOnEveryLayerPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$EnvironmentScanPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$FixedPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$HeightRangePlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$HeightmapPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementFilterAccess;
import dev.sixik.generator_accelerator.api.patches.GA$RandomOffsetPlacementAccess;
import dev.sixik.generator_accelerator.api.patches.GA$RepeatingPlacementAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CarvingMaskPlacement;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.FixedPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

import java.util.List;

public final class FeaturePlacementCompiler {
    private FeaturePlacementCompiler() {
    }

    public static FeatureProgram compile(List<PlacementModifier> placement, Holder<ConfiguredFeature<?, ?>> feature) {
        int size = placement.size();
        int[] opcodes = new int[size];
        PlacementModifier[] modifiers = new PlacementModifier[size];
        int fastOps = 0;
        int fallbackOps = 0;
        boolean linearFastOnly = true;
        HeightProvider[] heightProviders = new HeightProvider[size];
        Heightmap.Types[] heightmapTypes = new Heightmap.Types[size];
        FeatureProgram.RandomOffsetData[] randomOffsets = new FeatureProgram.RandomOffsetData[size];
        GA$RepeatingPlacementAccess[] repeatingPlacements = new GA$RepeatingPlacementAccess[size];
        GA$PlacementFilterAccess[] placementFilters = new GA$PlacementFilterAccess[size];
        FeatureProgram.FixedPlacementData[] fixedPlacements = new FeatureProgram.FixedPlacementData[size];
        GenerationStep.Carving[] carvingSteps = new GenerationStep.Carving[size];
        FeatureProgram.EnvironmentScanData[] environmentScans = new FeatureProgram.EnvironmentScanData[size];
        IntProvider[] countProviders = new IntProvider[size];
        GA$PlacementModifierExtension[] rawModifiers = new GA$PlacementModifierExtension[size];

        for (int i = 0; i < size; i++) {
            PlacementModifier modifier = placement.get(i);
            modifiers[i] = modifier;
            int opcode = opcodeFor(modifier);
            opcodes[i] = opcode;
            compileModifierData(
                    modifier,
                    opcode,
                    i,
                    heightProviders,
                    heightmapTypes,
                    randomOffsets,
                    repeatingPlacements,
                    placementFilters,
                    fixedPlacements,
                    carvingSteps,
                    environmentScans,
                    countProviders,
                    rawModifiers
            );
            if (opcode != FeatureOpcode.VANILLA_FALLBACK) {
                fastOps++;
            } else {
                fallbackOps++;
            }
            if (!isLinearFastOpcode(opcode)) {
                linearFastOnly = false;
            }
        }

        FeatureVmMetrics.recordProgramCompiled(fastOps, fallbackOps);
        int specializedExecutor = selectSpecializedExecutor(opcodes, fallbackOps);
        return new FeatureProgram(
                opcodes,
                modifiers,
                feature,
                fastOps,
                fallbackOps,
                linearFastOnly,
                specializedExecutor,
                heightProviders,
                heightmapTypes,
                randomOffsets,
                repeatingPlacements,
                placementFilters,
                fixedPlacements,
                carvingSteps,
                environmentScans,
                countProviders,
                rawModifiers
        );
    }

    private static int opcodeFor(PlacementModifier modifier) {
        if (modifier instanceof InSquarePlacement) return FeatureOpcode.IN_SQUARE;
        if (modifier instanceof HeightRangePlacement && modifier instanceof GA$HeightRangePlacementAccess) return FeatureOpcode.HEIGHT_RANGE;
        if (modifier instanceof HeightmapPlacement && modifier instanceof GA$HeightmapPlacementAccess) return FeatureOpcode.HEIGHTMAP;
        if (modifier instanceof RandomOffsetPlacement && modifier instanceof GA$RandomOffsetPlacementAccess) return FeatureOpcode.RANDOM_OFFSET;
        if (modifier instanceof RepeatingPlacement && modifier instanceof GA$RepeatingPlacementAccess) return FeatureOpcode.REPEATING;
        if (modifier instanceof BiomeFilter) return FeatureOpcode.BIOME_FILTER;
        if (modifier instanceof PlacementFilter && modifier instanceof GA$PlacementFilterAccess) return FeatureOpcode.PLACEMENT_FILTER;
        if (modifier instanceof FixedPlacement && modifier instanceof GA$FixedPlacementAccess) return FeatureOpcode.FIXED;
        if (modifier instanceof CarvingMaskPlacement && modifier instanceof GA$CarvingMaskPlacementAccess) return FeatureOpcode.CARVING_MASK;
        if (modifier instanceof EnvironmentScanPlacement && modifier instanceof GA$EnvironmentScanPlacementAccess) return FeatureOpcode.ENVIRONMENT_SCAN;
        if (modifier instanceof CountOnEveryLayerPlacement && modifier instanceof GA$CountOnEveryLayerPlacementAccess) return FeatureOpcode.COUNT_ON_EVERY_LAYER;
        if (modifier instanceof GA$PlacementModifierExtension extension && extension.ga$hasFastPositions()) return FeatureOpcode.RAW_MODIFIER;
        return FeatureOpcode.VANILLA_FALLBACK;
    }

    private static boolean isLinearFastOpcode(int opcode) {
        return opcode == FeatureOpcode.IN_SQUARE
                || opcode == FeatureOpcode.HEIGHT_RANGE
                || opcode == FeatureOpcode.HEIGHTMAP
                || opcode == FeatureOpcode.RANDOM_OFFSET
                || opcode == FeatureOpcode.PLACEMENT_FILTER
                || opcode == FeatureOpcode.BIOME_FILTER
                || opcode == FeatureOpcode.ENVIRONMENT_SCAN;
    }

    private static int selectSpecializedExecutor(int[] opcodes, int fallbackOps) {
        if (fallbackOps != 0 || opcodes.length < 3) {
            return FeatureExecutorKind.GENERIC;
        }
        if (opcodes[0] != FeatureOpcode.REPEATING || opcodes[1] != FeatureOpcode.IN_SQUARE) {
            return FeatureExecutorKind.GENERIC;
        }
        if (opcodes.length == 4 && isPlacementFilterOpcode(opcodes[3])) {
            if (opcodes[2] == FeatureOpcode.HEIGHT_RANGE) {
                return FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHT_RANGE_FILTER;
            }
            if (opcodes[2] == FeatureOpcode.HEIGHTMAP) {
                return FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHTMAP_FILTER;
            }
        }
        for (int i = 3; i < opcodes.length; i++) {
            if (!isLinearFastOpcode(opcodes[i])) {
                return FeatureExecutorKind.GENERIC;
            }
        }
        if (opcodes[2] == FeatureOpcode.HEIGHT_RANGE) {
            return FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHT_RANGE;
        }
        if (opcodes[2] == FeatureOpcode.HEIGHTMAP) {
            return FeatureExecutorKind.REPEATING_IN_SQUARE_HEIGHTMAP;
        }
        return FeatureExecutorKind.REPEATING_IN_SQUARE_LINEAR_TAIL;
    }

    private static boolean isPlacementFilterOpcode(int opcode) {
        return opcode == FeatureOpcode.PLACEMENT_FILTER || opcode == FeatureOpcode.BIOME_FILTER;
    }

    private static void compileModifierData(
            PlacementModifier modifier,
            int opcode,
            int index,
            HeightProvider[] heightProviders,
            Heightmap.Types[] heightmapTypes,
            FeatureProgram.RandomOffsetData[] randomOffsets,
            GA$RepeatingPlacementAccess[] repeatingPlacements,
            GA$PlacementFilterAccess[] placementFilters,
            FeatureProgram.FixedPlacementData[] fixedPlacements,
            GenerationStep.Carving[] carvingSteps,
            FeatureProgram.EnvironmentScanData[] environmentScans,
            IntProvider[] countProviders,
            GA$PlacementModifierExtension[] rawModifiers
    ) {
        switch (opcode) {
            case FeatureOpcode.HEIGHT_RANGE -> heightProviders[index] = ((GA$HeightRangePlacementAccess) modifier).ga$heightProvider();
            case FeatureOpcode.HEIGHTMAP -> heightmapTypes[index] = ((GA$HeightmapPlacementAccess) modifier).ga$heightmapType();
            case FeatureOpcode.RANDOM_OFFSET -> {
                GA$RandomOffsetPlacementAccess access = (GA$RandomOffsetPlacementAccess) modifier;
                randomOffsets[index] = new FeatureProgram.RandomOffsetData(access.ga$xzSpread(), access.ga$ySpread());
            }
            case FeatureOpcode.REPEATING -> repeatingPlacements[index] = (GA$RepeatingPlacementAccess) modifier;
            case FeatureOpcode.PLACEMENT_FILTER -> placementFilters[index] = (GA$PlacementFilterAccess) modifier;
            case FeatureOpcode.FIXED -> fixedPlacements[index] = new FeatureProgram.FixedPlacementData(((GA$FixedPlacementAccess) modifier).ga$fixedPositions());
            case FeatureOpcode.CARVING_MASK -> carvingSteps[index] = ((GA$CarvingMaskPlacementAccess) modifier).ga$carvingStep();
            case FeatureOpcode.ENVIRONMENT_SCAN -> {
                GA$EnvironmentScanPlacementAccess access = (GA$EnvironmentScanPlacementAccess) modifier;
                Direction direction = access.ga$directionOfSearch();
                environmentScans[index] = new FeatureProgram.EnvironmentScanData(
                        direction.getStepX(),
                        direction.getStepY(),
                        direction.getStepZ(),
                        access.ga$maxSteps()
                );
            }
            case FeatureOpcode.COUNT_ON_EVERY_LAYER -> countProviders[index] = ((GA$CountOnEveryLayerPlacementAccess) modifier).ga$countProvider();
            case FeatureOpcode.RAW_MODIFIER -> rawModifiers[index] = GA$PlacementModifierExtension.get(modifier);
            default -> {
            }
        }
    }
}
