package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.terrablender;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorContextRequirements;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import dev.sixik.generator_accelerator.common.surface_compiler.mask.Mask4096;
import java.util.Map;

public class VectorNamespacedRule implements VectorRule {
    private final VectorRule baseRule;
    private final Map<String, VectorRule> namespaceRules;

    public VectorNamespacedRule(VectorRule baseRule, Map<String, VectorRule> namespaceRules) {
        this.baseRule = baseRule;
        this.namespaceRules = namespaceRules;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx) {
        Mask4096 remainingMask = ctx.acquireMask4096();
        Mask4096 namespaceMask = ctx.acquireMask4096();
        try {
            remainingMask.or(activeMask);

            for (Map.Entry<String, VectorRule> entry : namespaceRules.entrySet()) {
                if (remainingMask.isEmpty()) break;

                String targetNamespace = entry.getKey();
                VectorRule rule = entry.getValue();

                if(rule == null) continue;

                int[] xzMatches = ctx.columnScratchMarks;
                int stamp = ctx.nextColumnScratchStamp();
                boolean hasAnyMatch = false;

                for (int xz = 0; xz < 256; xz++) {
                    Holder<Biome> biome = ctx.surfaceBiomes[xz];
                    if (biome != null) {
                        var biomeKey = biome.unwrapKey();
                        if (biomeKey.isPresent()
                                && biomeKey.get().location().getNamespace().equals(targetNamespace)) {
                            xzMatches[xz] = stamp;
                            hasAnyMatch = true;
                        }
                    }
                }

                if (!hasAnyMatch) continue;

                namespaceMask.clear();
                long[] words = remainingMask.words();
                for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                    long word = words[wordIndex];
                    while (word != 0L) {
                        int i = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                        if (xzMatches[i & 255] == stamp) {
                            namespaceMask.set(i);
                        }
                        word &= word - 1L;
                    }
                }

                if (!namespaceMask.isEmpty()) {
                    Mask4096 beforeApply = ctx.acquireMask4096();
                    try {
                        beforeApply.or(namespaceMask);

                        rule.apply(rawBlockData, namespaceMask, ctx);
                        beforeApply.xor(namespaceMask);
                        remainingMask.andNot(beforeApply);
                    } finally {
                        ctx.releaseMask4096(beforeApply);
                    }
                }
            }

            if (!remainingMask.isEmpty()) {
                baseRule.apply(rawBlockData, remainingMask, ctx);
            }

            activeMask.and(remainingMask);
        } finally {
            ctx.releaseMask4096(namespaceMask);
            ctx.releaseMask4096(remainingMask);
        }
    }

    @Override
    public int requiredContext() {
        int requirements = VectorContextRequirements.SURFACE_BIOMES | this.baseRule.requiredContext();
        for (VectorRule rule : this.namespaceRules.values()) {
            if (rule != null) {
                requirements |= rule.requiredContext();
            }
        }
        return requirements;
    }
}
