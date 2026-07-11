package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRuleCompiler;
import dev.sixik.generator_accelerator.common.utils.FastPositionalRandom;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;

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
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
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
            Mask4096 ruleMask = ctx.acquireMask4096();
            try {
                ruleMask.or(activeMask);
                long[] words = ruleMask.words();
                for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                    long word = words[wordIndex];
                    while (word != 0L) {
                        int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                        if (ruleByColumn[i & 255] != ruleIndex) {
                            ruleMask.clear(i);
                        }
                        word &= word - 1L;
                    }
                }

                if (!ruleMask.isEmpty()) {
                    Mask4096 originalRuleMask = ctx.acquireMask4096();
                    try {
                        originalRuleMask.or(ruleMask);

                        rule.apply(rawBlockData, ruleMask, ctx);

                        originalRuleMask.xor(ruleMask);

                        activeMask.andNot(originalRuleMask);
                    } finally {
                        ctx.releaseMask4096(originalRuleMask);
                    }
                }
            } finally {
                ctx.releaseMask4096(ruleMask);
            }
        }
    }

    @Override
    public int requiredContext() {
        int requirements = 0;
        for (VectorRule rule : this.compiledRules) {
            requirements |= rule.requiredContext();
        }
        return requirements;
    }
}
