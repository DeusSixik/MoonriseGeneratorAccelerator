package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.utils.FastPositionalRandom;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsContext;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsRuleSource;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BetweenRepeatingNoiseRange;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.WeightedRuleSource;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;

final class BiomesWeveGoneSurfaceCompilerData {
    private static Field repeatingRuleField;

    private BiomesWeveGoneSurfaceCompilerData() {
    }

    @Nullable
    static SurfaceRuleNode compileRule(SurfaceRules.RuleSource ruleSource, SurfaceCompilerContext compiler) {
        if (ruleSource instanceof BandsRuleSource bandsRule) {
            return new BwgBandsSurfaceRuleNode(bandsRule);
        }

        if (ruleSource instanceof WeightedRuleSource weightedRule) {
            return new BwgWeightedSurfaceRuleNode(weightedRule, compiler);
        }

        if (ruleSource instanceof BetweenRepeatingNoiseRange repeatingRange) {
            try {
                SurfaceRules.RuleSource innerRule = (SurfaceRules.RuleSource) repeatingRuleField().get(repeatingRange);
                return compiler.compileRule(innerRule);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to compile BWG BetweenRepeatingNoiseRange", e);
            }
        }

        return null;
    }

    @Nullable
    static SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource, SurfaceCompilerContext compiler) {
        return null;
    }

    private static Field repeatingRuleField() throws ReflectiveOperationException {
        Field field = repeatingRuleField;
        if (field == null) {
            field = BetweenRepeatingNoiseRange.class.getDeclaredField("rule");
            field.setAccessible(true);
            repeatingRuleField = field;
        }
        return field;
    }

    private static final class BwgBandsSurfaceRuleNode implements SurfaceRuleNode {
        private final BandsRuleSource source;

        private BwgBandsSurfaceRuleNode(BandsRuleSource source) {
            this.source = source;
        }

        @Override
        public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            if (!(ctx.surfaceSystem instanceof BandsContext bandsContext)) {
                return;
            }

            long[] words = activeMask.words();
            for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                long word = words[wordIndex];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int index = (wordIndex << 6) + bit;
                    int localX = index & 15;
                    int localZ = (index >> 4) & 15;
                    int localY = index >> 8;

                    BlockState state = bandsContext.getBandsState(
                            this.source,
                            this.source.bandStates(),
                            this.source.bandSizeProvider(),
                            this.source.bandsCountProvider(),
                            ctx.sectionStartX + localX,
                            ctx.sectionStartY + localY,
                            ctx.sectionStartZ + localZ,
                            this.source.frequency(),
                            this.source.noiseScale()
                    );

                    if (state != null) {
                        rawBlockData[index] = Block.getId(state);
                        activeMask.clear(index);
                    }
                    word &= word - 1L;
                }
            }
        }

        @Override
        public int requirements() {
            return SurfaceRequirements.NOISE | SurfaceRequirements.RANDOM;
        }
    }

    private static final class BwgWeightedSurfaceRuleNode implements SurfaceRuleNode {
        private final SurfaceRuleNode[] uniqueRules;
        private final int[] compiledRuleToGroup;
        private final int[] weights;
        private final int totalWeight;
        private final int requirements;

        private BwgWeightedSurfaceRuleNode(WeightedRuleSource original, SurfaceCompilerContext compiler) {
            var unwrap = original.ruleSources().unwrap();
            this.weights = new int[unwrap.size()];

            int total = 0;
            int req = SurfaceRequirements.RANDOM;
            IdentityHashMap<SurfaceRuleNode, Integer> groups = new IdentityHashMap<>();
            SurfaceRuleNode[] groupRules = new SurfaceRuleNode[unwrap.size()];
            this.compiledRuleToGroup = new int[unwrap.size()];
            int groupCount = 0;

            for (int i = 0; i < unwrap.size(); i++) {
                WeightedEntry.Wrapper<SurfaceRules.RuleSource> wrapper = unwrap.get(i);
                SurfaceRuleNode compiledRule = compiler.compileRule(wrapper.data());
                this.weights[i] = wrapper.getWeight().asInt();
                total += this.weights[i];
                req |= compiledRule.requirements();

                Integer group = groups.get(compiledRule);
                if (group == null) {
                    group = groupCount++;
                    groups.put(compiledRule, group);
                    groupRules[group] = compiledRule;
                }
                this.compiledRuleToGroup[i] = group;
            }

            this.uniqueRules = new SurfaceRuleNode[groupCount];
            System.arraycopy(groupRules, 0, this.uniqueRules, 0, groupCount);
            this.totalWeight = total;
            this.requirements = req;
        }

        @Override
        public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            int mark = scratch.mark();
            int groupMaskBase = scratch.reserveMasks(this.uniqueRules.length);
            Mask4096 processedMask = scratch.pushMask();
            PositionalRandomFactory randomFactory = ctx.surfaceSystem.noiseRandom;
            activeMask.computeActiveColumns(scratch.activeColumns);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int randomRoll = FastPositionalRandom.nextIntAt(randomFactory, x, 0, z, this.totalWeight);
                    int chosenRule = this.weights.length - 1;
                    int currentWeight = 0;

                    for (int i = 0; i < this.weights.length; i++) {
                        currentWeight += this.weights[i];
                        if (randomRoll < currentWeight) {
                            chosenRule = i;
                            break;
                        }
                    }

                    int xz = x | (z << 4);
                    if ((scratch.activeColumns[xz >>> 6] & (1L << (xz & 63))) != 0L) {
                        scratch.transientMask(groupMaskBase + this.compiledRuleToGroup[chosenRule]).orColumnFrom(activeMask, xz);
                    }
                }
            }

            for (int group = 0; group < this.uniqueRules.length; group++) {
                Mask4096 ruleMask = scratch.transientMask(groupMaskBase + group);
                if (ruleMask.isEmpty()) {
                    continue;
                }

                processedMask.copyFrom(ruleMask);
                this.uniqueRules[group].apply(rawBlockData, ruleMask, ctx, scratch);
                processedMask.xor(ruleMask);
                activeMask.andNot(processedMask);
            }

            scratch.restore(mark);
        }

        @Override
        public int requirements() {
            return this.requirements;
        }
    }
}
