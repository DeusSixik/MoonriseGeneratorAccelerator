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
        BitSet remainingMask = (BitSet) activeMask.clone();

        for (Map.Entry<String, VectorRule> entry : namespaceRules.entrySet()) {
            if (remainingMask.isEmpty()) break;

            String targetNamespace = entry.getKey();
            VectorRule rule = entry.getValue();

            if(rule == null) continue;

            boolean[] xzMatches = new boolean[256];
            boolean hasAnyMatch = false;

            for (int xz = 0; xz < 256; xz++) {
                Holder<Biome> biome = ctx.surfaceBiomes[xz];
                if (biome != null && biome.unwrapKey().isPresent()) {
                    if (biome.unwrapKey().get().location().getNamespace().equals(targetNamespace)) {
                        xzMatches[xz] = true;
                        hasAnyMatch = true;
                    }
                }
            }

            if (!hasAnyMatch) continue;

            BitSet namespaceMask = new BitSet(4096);
            for (int i = remainingMask.nextSetBit(0); i >= 0; i = remainingMask.nextSetBit(i + 1)) {
                if (xzMatches[i & 255]) {
                    namespaceMask.set(i);
                }
            }

            if (!namespaceMask.isEmpty()) {
                BitSet beforeApply = (BitSet) namespaceMask.clone();

                rule.apply(rawBlockData, namespaceMask, ctx);
                beforeApply.xor(namespaceMask);
                remainingMask.andNot(beforeApply);
            }
        }

        if (!remainingMask.isEmpty()) {
            baseRule.apply(rawBlockData, remainingMask, ctx);
        }

        activeMask.and(remainingMask);
    }
}
