package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.common.surface.vector.rules.*;
import dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone.BiomesWeveGone$CompilerData;
import dev.sixik.generator_accelerator.common.surface.vector.rules.compat.lithostitched.Lithostitched$CompilerData;
import dev.sixik.generator_accelerator.common.surface.vector.rules.compat.terrablender.Terrablender$CompilerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.ArrayList;
import java.util.List;

public class VectorRuleCompiler {

    public static VectorRule compileRule(SurfaceRules.RuleSource ruleSource) {

        if (ruleSource instanceof SurfaceRules.BlockRuleSource blockRule) {
            return new VectorBlockRule(blockRule.resultState());
        }

        if (ruleSource instanceof SurfaceRules.TestRuleSource testRule) {
            VectorCondition compiledCondition = compileCondition(testRule.ifTrue());
            VectorRule compiledAction = compileRule(testRule.thenRun());
            return new VectorTestRule(compiledCondition, compiledAction);
        }

        if (ruleSource instanceof SurfaceRules.SequenceRuleSource sequenceRule) {
            List<VectorRule> compiledList = new ArrayList<>();
            for (SurfaceRules.RuleSource childRule : sequenceRule.sequence()) {
                compiledList.add(compileRule(childRule));
            }
            return new VectorSequenceRule(compiledList.toArray(new VectorRule[0]));
        }

        if(ruleSource == SurfaceRules.Bandlands.INSTANCE) {
            return VectorBandlandsRule.INSTANCE;
        }

        if(lithostitched_loaded) {
            final var ret = Lithostitched$CompilerData.compileRule(ruleSource);
            if (ret != null) return ret;
        }

        if(terrablender_loaded) {
            final var ret = Terrablender$CompilerData.compileRule(ruleSource);
            if (ret != null) return ret;
        }

        if(biomeswevegone_loaded) {
            final var ret = BiomesWeveGone$CompilerData.compileRule(ruleSource);
            if (ret != null) return ret;
        }

        throw new RuntimeException("Unknown rules: " + ruleSource.getClass());
    }

    public static VectorCondition compileCondition(SurfaceRules.ConditionSource conditionSource) {

        if (conditionSource instanceof SurfaceRules.BiomeConditionSource biomeCond) {
            return new VectorBiomeCondition(biomeCond.biomes);
        }

        if (conditionSource instanceof SurfaceRules.StoneDepthCheck depthCond) {
            return new VectorStoneDepthCondition(
                    depthCond.offset(),
                    depthCond.addSurfaceDepth(),
                    depthCond.secondaryDepthRange(),
                    depthCond.surfaceType()
            );
        }

        if (conditionSource instanceof SurfaceRules.YConditionSource yCond) {
            return new VectorYCondition(
                    yCond.anchor(),
                    yCond.surfaceDepthMultiplier(),
                    yCond.addStoneDepth()
            );
        }

        if (conditionSource instanceof SurfaceRules.NotConditionSource notCond) {
            return new VectorNotCondition(compileCondition(notCond.target()));
        }

        if(conditionSource instanceof SurfaceRules.NoiseThresholdConditionSource cond) {
            return new VectorNoiseThresholdCondition(cond.noise(), cond.minThreshold(), cond.maxThreshold());
        }

        if(conditionSource instanceof SurfaceRules.VerticalGradientConditionSource cond) {
            return new VectorVerticalGradientCondition(cond.trueAtAndBelow(), cond.falseAtAndAbove(), cond.randomName());
        }

        if (conditionSource == SurfaceRules.AbovePreliminarySurface.INSTANCE) {
            return VectorAbovePreliminarySurfaceCondition.INSTANCE;
        }

        if(conditionSource instanceof SurfaceRules.WaterConditionSource cond) {
            return new VectorWaterCondition(cond.offset(), cond.surfaceDepthMultiplier(), cond.addStoneDepth());
        }

        if(conditionSource == SurfaceRules.Temperature.INSTANCE) {
            return VectorTemperatureCondition.INSTANCE;
        }

        if(conditionSource == SurfaceRules.Steep.INSTANCE) {
            return VectorSteepCondition.INSTANCE;
        }

        if(conditionSource == SurfaceRules.Hole.INSTANCE) {
            return VectorHoleCondition.INSTANCE;
        }

        if(lithostitched_loaded) {
            final var ret = Lithostitched$CompilerData.compileCondition(conditionSource);
            if (ret != null) return ret;
        }

        throw new UnsupportedOperationException("Unknown condition: " + conditionSource.getClass());
    }

    private static boolean lithostitched_loaded = false;
    private static boolean terrablender_loaded = false;
    private static boolean biomeswevegone_loaded = false;

    private static boolean isLoaded(String classz) {
        try {
            Class.forName(classz, false, VectorRuleCompiler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }

    static {
        lithostitched_loaded = isLoaded("dev.worldgen.lithostitched.Lithostitched");
        terrablender_loaded = isLoaded("terrablender.core.TerraBlender");
        biomeswevegone_loaded = isLoaded("net.potionstudios.biomeswevegone.BiomesWeveGone");
    }

}
