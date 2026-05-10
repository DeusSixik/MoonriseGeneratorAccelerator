package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import dev.sixik.generator_accelerator.common.utils.FastPositionalRandom;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource;

import java.util.BitSet;

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
            this.compiledRules[i] = VectorRuleCompiler.compileRule(wrapper.data());
            this.weights[i] = wrapper.getWeight().asInt();
            total += this.weights[i];
        }
        this.totalWeight = total;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        PositionalRandomFactory randomFactory = ctx.surfaceSystem.noiseRandom;
        int[] ruleByColumn = ctx.weightedRuleByColumn;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int randomRoll = FastPositionalRandom.nextIntAt(randomFactory, x, 0, z, this.totalWeight);
                int chosenRuleIndex = compiledRules.length - 1;
                int currentWeight = 0;

                for (int i = 0; i < compiledRules.length; i++) {
                    currentWeight += weights[i];
                    if (randomRoll < currentWeight) {
                        chosenRuleIndex = i;
                        break;
                    }
                }

                ruleByColumn[x | (z << 4)] = chosenRuleIndex;
            }
        }

        for (int ruleIndex = 0; ruleIndex < this.compiledRules.length; ruleIndex++) {
            VectorRule rule = this.compiledRules[ruleIndex];
            BitSet ruleMask = ctx.acquireBitSet4096();
            try {
                ruleMask.or(activeMask);
                for (int i = ruleMask.nextSetBit(0); i >= 0; i = ruleMask.nextSetBit(i + 1)) {
                    if (ruleByColumn[i & 255] != ruleIndex) {
                        ruleMask.clear(i);
                    }
                }

                if (!ruleMask.isEmpty()) {
                    BitSet originalRuleMask = ctx.acquireBitSet4096();
                    try {
                        originalRuleMask.or(ruleMask);

                        rule.apply(rawBlockData, ruleMask, ctx);

                        originalRuleMask.xor(ruleMask);

                        activeMask.andNot(originalRuleMask);
                    } finally {
                        ctx.releaseBitSet4096(originalRuleMask);
                    }
                }
            } finally {
                ctx.releaseBitSet4096(ruleMask);
            }
        }
    }
}
