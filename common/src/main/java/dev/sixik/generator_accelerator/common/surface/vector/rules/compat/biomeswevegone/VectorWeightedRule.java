package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public class VectorWeightedRule implements VectorRule {

    private final VectorRule[] compiledRules;
    private final int[] weights;
    private final int totalWeight;

    public VectorWeightedRule(WeightedRuleSource original) {
        var unwrap = original.ruleSources().unwrap();
        this.compiledRules = new VectorRule[unwrap.size()];
        this.weights = new int[unwrap.size()];

        int total = 0;
        for (int i = 0; i < unwrap.size(); i++) {
            WeightedEntry.Wrapper<SurfaceRules.RuleSource> wrapper = unwrap.get(i);
            this.compiledRules[i] = VectorRuleCompiler.compileRule(wrapper.data);
            this.weights[i] = wrapper.getWeight().asInt();
            total += this.weights[i];
        }
        this.totalWeight = total;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        PositionalRandomFactory randomFactory = ctx.surfaceSystem.noiseRandom;

        Map<VectorRule, BitSet> ruleToColumns = new HashMap<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RandomSource random = randomFactory.at(x, 0, z);

                int randomRoll = random.nextInt(this.totalWeight);
                VectorRule chosenRule = compiledRules[compiledRules.length - 1]; // Фоллбэк
                int currentWeight = 0;

                for (int i = 0; i < compiledRules.length; i++) {
                    currentWeight += weights[i];
                    if (randomRoll < currentWeight) {
                        chosenRule = compiledRules[i];
                        break;
                    }
                }

                ruleToColumns.computeIfAbsent(chosenRule, k -> new BitSet(256)).set(x | (z << 4));
            }
        }

        for (Map.Entry<VectorRule, BitSet> entry : ruleToColumns.entrySet()) {
            VectorRule rule = entry.getKey();
            BitSet columnMaskXZ = entry.getValue();

            BitSet ruleMask = (BitSet) activeMask.clone();
            for (int i = ruleMask.nextSetBit(0); i >= 0; i = ruleMask.nextSetBit(i + 1)) {
                if (!columnMaskXZ.get(i & 255)) {
                    ruleMask.clear(i);
                }
            }

            if (!ruleMask.isEmpty()) {
                BitSet originalRuleMask = (BitSet) ruleMask.clone();

                rule.apply(rawBlockData, ruleMask, ctx);

                originalRuleMask.xor(ruleMask);

                activeMask.andNot(originalRuleMask);
            }
        }
    }
}
