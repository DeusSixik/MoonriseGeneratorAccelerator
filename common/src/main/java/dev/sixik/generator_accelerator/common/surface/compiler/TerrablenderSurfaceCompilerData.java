package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;

import java.util.Map;

final class TerrablenderSurfaceCompilerData {
    private TerrablenderSurfaceCompilerData() {
    }

    @Nullable
    static SurfaceRuleNode compileRule(SurfaceRules.RuleSource ruleSource, SurfaceCompilerContext compiler) {
        if (!(ruleSource instanceof NamespacedSurfaceRuleSource namespacedRule)) {
            return null;
        }

        SurfaceRuleNode baseRule = compiler.compileRule(namespacedRule.base());
        Map<String, SurfaceRules.RuleSource> sources = namespacedRule.sources();
        String[] namespaces = new String[sources.size()];
        SurfaceRuleNode[] rules = new SurfaceRuleNode[sources.size()];

        int i = 0;
        for (Map.Entry<String, SurfaceRules.RuleSource> entry : sources.entrySet()) {
            namespaces[i] = entry.getKey();
            rules[i] = compiler.compileRule(entry.getValue());
            i++;
        }

        return new NamespacedSurfaceRuleNode(baseRule, namespaces, rules);
    }

    @Nullable
    static SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource, SurfaceCompilerContext compiler) {
        return null;
    }

    private static final class NamespacedSurfaceRuleNode implements SurfaceRuleNode {
        private final SurfaceRuleNode baseRule;
        private final String[] namespaces;
        private final SurfaceRuleNode[] rules;
        private final int requirements;

        private NamespacedSurfaceRuleNode(SurfaceRuleNode baseRule, String[] namespaces, SurfaceRuleNode[] rules) {
            this.baseRule = baseRule;
            this.namespaces = namespaces;
            this.rules = rules;

            int req = baseRule.requirements() | SurfaceRequirements.BIOME;
            for (SurfaceRuleNode rule : rules) {
                req |= rule.requirements();
            }
            this.requirements = req;
        }

        @Override
        public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            int mark = scratch.mark();
            Mask4096 remainingMask = scratch.pushMask();
            Mask4096 namespaceMask = scratch.pushMask();
            Mask4096 processedMask = scratch.pushMask();
            remainingMask.copyFrom(activeMask);
            String[] biomeNamespaces = scratch.biomeNamespaces;
            for (int xz = 0; xz < 256; xz++) {
                Holder<Biome> biome = ctx.surfaceBiomes[xz];
                var key = biome == null ? java.util.Optional.<net.minecraft.resources.ResourceKey<Biome>>empty() : biome.unwrapKey();
                biomeNamespaces[xz] = key.isPresent() ? key.get().location().getNamespace() : null;
            }

            String[] localNamespaces = this.namespaces;
            SurfaceRuleNode[] localRules = this.rules;
            for (int ruleIndex = 0; ruleIndex < localRules.length; ruleIndex++) {
                if (remainingMask.isEmpty()) {
                    break;
                }

                namespaceMask.clear();
                String targetNamespace = localNamespaces[ruleIndex];
                remainingMask.computeActiveColumns(scratch.activeColumns);
                for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                    long columnWord = scratch.activeColumns[columnWordIndex];
                    while (columnWord != 0L) {
                        int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                        if (targetNamespace.equals(biomeNamespaces[xz])) {
                            namespaceMask.orColumnFrom(remainingMask, xz);
                        }
                        columnWord &= columnWord - 1L;
                    }
                }

                if (namespaceMask.isEmpty()) {
                    continue;
                }

                processedMask.copyFrom(namespaceMask);
                localRules[ruleIndex].apply(rawBlockData, namespaceMask, ctx, scratch);
                processedMask.xor(namespaceMask);
                remainingMask.andNot(processedMask);
            }

            if (!remainingMask.isEmpty()) {
                this.baseRule.apply(rawBlockData, remainingMask, ctx, scratch);
            }

            activeMask.and(remainingMask);
            scratch.restore(mark);
        }

        @Override
        public int requirements() {
            return this.requirements;
        }
    }
}
