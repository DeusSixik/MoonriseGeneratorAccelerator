package dev.sixik.generator_accelerator.common.surface.vector;

import dev.sixik.generator_accelerator.common.surface.vector.rules.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.ArrayList;
import java.util.List;

public class VectorRuleCompiler {

    // --- КОМПИЛЯЦИЯ ПРАВИЛ (RuleSource -> VectorRule) ---
    public static VectorRule compileRule(SurfaceRules.RuleSource ruleSource) {

        // 1. Блок (Базовый случай)
        if (ruleSource instanceof SurfaceRules.BlockRuleSource blockRule) {
            return new VectorBlockRule(blockRule.resultState());
        }

        // 2. Условие (ifTrue -> thenRun)
        if (ruleSource instanceof SurfaceRules.TestRuleSource testRule) {
            VectorCondition compiledCondition = compileCondition(testRule.ifTrue());
            VectorRule compiledAction = compileRule(testRule.thenRun());
            return new VectorTestRule(compiledCondition, compiledAction);
        }

        // 3. Последовательность (Массив правил)
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

        throw new RuntimeException("Unknown rules: " + ruleSource.getClass());
    }

    // --- КОМПИЛЯЦИЯ УСЛОВИЙ (ConditionSource -> VectorCondition) ---
    public static VectorCondition compileCondition(SurfaceRules.ConditionSource conditionSource) {

        // 1. Биомы
        if (conditionSource instanceof SurfaceRules.BiomeConditionSource biomeCond) {
            // В ванилле это скрытое поле (нужно использовать Accessor Mixin или Reflection,
            // если нет публичного геттера. Обычно в record поле называется как параметр конструктора)
            List<ResourceKey<Biome>> biomes = extractBiomes(biomeCond);
            return new VectorBiomeCondition(biomes);
        }

        // 2. Глубина камня
        if (conditionSource instanceof SurfaceRules.StoneDepthCheck depthCond) {
            return new VectorStoneDepthCondition(
                    depthCond.offset(),
                    depthCond.addSurfaceDepth(),
                    depthCond.secondaryDepthRange(),
                    depthCond.surfaceType()
            );
        }

        // 3. Проверка высоты Y
        if (conditionSource instanceof SurfaceRules.YConditionSource yCond) {
            return new VectorYCondition(
                    yCond.anchor(),
                    yCond.surfaceDepthMultiplier(),
                    yCond.addStoneDepth()
            );
        }

        // 4. Инверсия (NOT)
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



        // ... остальные условия (Water, NoiseThreshold и т.д.) ...

        throw new UnsupportedOperationException("Unknown condition: " + conditionSource.getClass());
    }

    // Вспомогательный метод для извлечения списка биомов из ванильного BiomeConditionSource
    // Если поле приватное, используй Mixin Accessor (Interface с @Accessor("biomes"))
    private static List<ResourceKey<Biome>> extractBiomes(SurfaceRules.BiomeConditionSource cond) {
        return cond.biomes;
    }
}
