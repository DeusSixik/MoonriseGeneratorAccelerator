package dev.sixik.generator_accelerator.common.surface.compiler.ir;

import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.List;

public sealed interface SurfaceConditionIR permits
        SurfaceConditionIR.Constant,
        SurfaceConditionIR.BiomeCondition,
        SurfaceConditionIR.HolderSetBiomeCondition,
        SurfaceConditionIR.StoneDepth,
        SurfaceConditionIR.Y,
        SurfaceConditionIR.NoiseThreshold,
        SurfaceConditionIR.VerticalGradient,
        SurfaceConditionIR.AbovePreliminarySurface,
        SurfaceConditionIR.Water,
        SurfaceConditionIR.Temperature,
        SurfaceConditionIR.Steep,
        SurfaceConditionIR.Hole,
        SurfaceConditionIR.Not,
        SurfaceConditionIR.AllOf,
        SurfaceConditionIR.AnyOf,
        SurfaceConditionIR.FallbackCondition {

    record Constant(boolean value) implements SurfaceConditionIR {
    }

    record BiomeCondition(List<ResourceKey<Biome>> biomes) implements SurfaceConditionIR {
        public BiomeCondition {
            biomes = List.copyOf(biomes);
        }
    }

    record HolderSetBiomeCondition(HolderSet<Biome> biomes) implements SurfaceConditionIR {
    }

    record StoneDepth(int offset, boolean addSurfaceDepth, int secondaryDepthRange, CaveSurface surfaceType) implements SurfaceConditionIR {
    }

    record Y(VerticalAnchor anchor, int surfaceDepthMultiplier, boolean addStoneDepth) implements SurfaceConditionIR {
    }

    record NoiseThreshold(ResourceKey<NormalNoise.NoiseParameters> noiseKey, double minThreshold, double maxThreshold) implements SurfaceConditionIR {
    }

    record VerticalGradient(VerticalAnchor trueAtAndBelow, VerticalAnchor falseAtAndAbove, ResourceLocation randomName) implements SurfaceConditionIR {
    }

    record AbovePreliminarySurface() implements SurfaceConditionIR {
    }

    record Water(int offset, int surfaceDepthMultiplier, boolean addStoneDepth) implements SurfaceConditionIR {
    }

    record Temperature() implements SurfaceConditionIR {
    }

    record Steep() implements SurfaceConditionIR {
    }

    record Hole() implements SurfaceConditionIR {
    }

    record Not(SurfaceConditionIR target) implements SurfaceConditionIR {
    }

    record AllOf(List<SurfaceConditionIR> conditions) implements SurfaceConditionIR {
        public AllOf {
            conditions = List.copyOf(conditions);
        }
    }

    record AnyOf(List<SurfaceConditionIR> conditions) implements SurfaceConditionIR {
        public AnyOf {
            conditions = List.copyOf(conditions);
        }
    }

    record FallbackCondition(SurfaceRules.ConditionSource source) implements SurfaceConditionIR {
    }
}
