package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.terrablender;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.BitSet;
import java.util.Map;

public class VectorNamespacedRule implements VectorRule {
    private final VectorRule baseRule;
    private final Map<String, VectorRule> namespaceRules;

    public VectorNamespacedRule(VectorRule baseRule, Map<String, VectorRule> namespaceRules) {
        this.baseRule = baseRule;
        this.namespaceRules = namespaceRules;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        BitSet remainingMask = ctx.acquireBitSet4096();
        BitSet namespaceMask = ctx.acquireBitSet4096();
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
                for (int i = remainingMask.nextSetBit(0); i >= 0; i = remainingMask.nextSetBit(i + 1)) {
                    if (xzMatches[i & 255] == stamp) {
                        namespaceMask.set(i);
                    }
                }

                if (!namespaceMask.isEmpty()) {
                    BitSet beforeApply = ctx.acquireBitSet4096();
                    try {
                        beforeApply.or(namespaceMask);

                        rule.apply(rawBlockData, namespaceMask, ctx);
                        beforeApply.xor(namespaceMask);
                        remainingMask.andNot(beforeApply);
                    } finally {
                        ctx.releaseBitSet4096(beforeApply);
                    }
                }
            }

            if (!remainingMask.isEmpty()) {
                baseRule.apply(rawBlockData, remainingMask, ctx);
            }

            activeMask.and(remainingMask);
        } finally {
            ctx.releaseBitSet4096(namespaceMask);
            ctx.releaseBitSet4096(remainingMask);
        }
    }
}
