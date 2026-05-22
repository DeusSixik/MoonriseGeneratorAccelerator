package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.worldgen.lithostitched.impl.worldgen.bandlands.Bandlands;
import dev.worldgen.lithostitched.worldgen.surface.condition.AllOfCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.AnyOfCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.BiomeCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.SlopeCondition;
import dev.worldgen.lithostitched.worldgen.surface.condition.internal.TagFilledCondition;
import dev.worldgen.lithostitched.worldgen.surface.rule.BandlandsRule;
import dev.worldgen.lithostitched.worldgen.surface.rule.ReferenceRule;
import dev.worldgen.lithostitched.worldgen.surface.rule.TransientMergedRule;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class LithostitchedSurfaceCompilerData {
    private LithostitchedSurfaceCompilerData() {
    }

    @Nullable
    static SurfaceRuleNode compileRule(SurfaceRules.RuleSource ruleSource, SurfaceCompilerContext compiler) {
        if (ruleSource instanceof TransientMergedRule mergedRule) {
            List<SurfaceRules.RuleSource> rules = mergedRule.rules();
            if (rules.size() == 1) {
                return compiler.compileRule(rules.getFirst());
            }

            List<SurfaceRuleNode> compiledList = new ObjectArrayList<>(rules.size() + 1);
            for (SurfaceRules.RuleSource childRule : rules) {
                compiledList.add(compiler.compileRule(childRule));
            }
            compiledList.add(compiler.compileRule(mergedRule.original()));
            return new SequenceSurfaceRuleNode(compiledList.toArray(new SurfaceRuleNode[0]));
        }

        if (ruleSource instanceof ReferenceRule refRule) {
            int size = refRule.rules().size();
            if (size == 0) {
                return EmptySurfaceRuleNode.INSTANCE;
            }
            if (size == 1) {
                return compiler.compileRule(refRule.rules().get(0).value());
            }

            SurfaceRuleNode[] rules = new SurfaceRuleNode[size];
            for (int i = 0; i < size; i++) {
                rules[i] = compiler.compileRule(refRule.rules().get(i).value());
            }
            return new SequenceSurfaceRuleNode(rules);
        }

        if (ruleSource instanceof BandlandsRule bandlandsRule) {
            return new LithoBandlandsSurfaceRuleNode(bandlandsRule.options().value());
        }

        return null;
    }

    @Nullable
    static SurfaceConditionNode compileCondition(SurfaceRules.ConditionSource conditionSource, SurfaceCompilerContext compiler) {
        if (conditionSource instanceof SlopeCondition slopeCondition) {
            return new LithoSlopeSurfaceConditionNode(slopeCondition.threshold());
        }

        if (conditionSource instanceof BiomeCondition biomeCondition) {
            return new HolderSetBiomeSurfaceConditionNode(biomeCondition.biomes());
        }

        if (conditionSource instanceof AnyOfCondition anyOf) {
            SurfaceConditionNode[] conditions = new SurfaceConditionNode[anyOf.conditions().size()];
            for (int i = 0; i < conditions.length; i++) {
                conditions[i] = compiler.compileCondition(anyOf.conditions().get(i));
            }
            return new AnyOfSurfaceConditionNode(conditions);
        }

        if (conditionSource instanceof AllOfCondition allOf) {
            SurfaceConditionNode[] conditions = new SurfaceConditionNode[allOf.conditions().size()];
            for (int i = 0; i < conditions.length; i++) {
                conditions[i] = compiler.compileCondition(allOf.conditions().get(i));
            }
            return new AllOfSurfaceConditionNode(conditions);
        }

        if (conditionSource instanceof TagFilledCondition tagFilledCondition) {
            return tagFilledCondition.rules().size() == 0 ? ConstantSurfaceConditionNode.FALSE : ConstantSurfaceConditionNode.TRUE;
        }

        return null;
    }

    private static final class LithoBandlandsSurfaceRuleNode implements SurfaceRuleNode {
        private final Bandlands bandlands;

        private LithoBandlandsSurfaceRuleNode(Bandlands bandlands) {
            this.bandlands = bandlands;
        }

        @Override
        public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            long[] words = activeMask.words();
            for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
                long word = words[wordIndex];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int index = (wordIndex << 6) + bit;
                    int localX = index & 15;
                    int localZ = (index >> 4) & 15;
                    int globalX = ctx.sectionStartX * 16 + localX;
                    int globalZ = ctx.sectionStartZ * 16 + localZ;
                    int y = ctx.surfaceHeights[index & 255];

                    BlockState state = this.bandlands.getBand(ctx.surfaceSystem, globalX, y, globalZ);
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
            return SurfaceRequirements.SURFACE_DEPTH;
        }
    }

    private static final class LithoSlopeSurfaceConditionNode implements SurfaceConditionNode {
        private final int minDiff;
        private final int maxDiff;

        private LithoSlopeSurfaceConditionNode(InclusiveRange<Integer> threshold) {
            this.minDiff = threshold.minInclusive();
            this.maxDiff = threshold.maxInclusive();
        }

        @Override
        public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
            short[] heights = ctx.surfaceHeights;
            activeMask.computeActiveColumns(scratch.activeColumns);
            for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                long columnWord = scratch.activeColumns[columnWordIndex];
                while (columnWord != 0L) {
                    int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);

                    int x = xz & 15;
                    int z = (xz >> 4) & 15;
                    int north = Math.max(z - 1, 0);
                    int south = Math.min(z + 1, 15);
                    int west = Math.max(x - 1, 0);
                    int east = Math.min(x + 1, 15);

                    int hNorth = heights[x | (north << 4)];
                    int hSouth = heights[x | (south << 4)];
                    int hWest = heights[west | (z << 4)];
                    int hEast = heights[east | (z << 4)];

                    int maxH = hNorth;
                    if (hSouth > maxH) maxH = hSouth;
                    if (hWest > maxH) maxH = hWest;
                    if (hEast > maxH) maxH = hEast;

                    int minH = hNorth;
                    if (hSouth < minH) minH = hSouth;
                    if (hWest < minH) minH = hWest;
                    if (hEast < minH) minH = hEast;

                    int diff = maxH - minH;
                    if (diff < this.minDiff || diff > this.maxDiff) {
                        activeMask.clearColumn(xz);
                    }
                    columnWord &= columnWord - 1L;
                }
            }
        }

        @Override
        public int requirements() {
            return SurfaceRequirements.SLOPE;
        }
    }
}
