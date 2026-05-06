package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.List;

interface SurfaceRuleNode {
    void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch);

    int requirements();

    default boolean mayWriteFluid() {
        return true;
    }
}

interface SurfaceConditionNode {
    void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch);

    int requirements();
}

final class EmptySurfaceRuleNode implements SurfaceRuleNode {
    static final EmptySurfaceRuleNode INSTANCE = new EmptySurfaceRuleNode();

    private EmptySurfaceRuleNode() {
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
    }

    @Override
    public int requirements() {
        return 0;
    }

    @Override
    public boolean mayWriteFluid() {
        return false;
    }
}

final class BlockSurfaceRuleNode implements SurfaceRuleNode {
    private final int blockId;
    private final boolean mayWriteFluid;

    BlockSurfaceRuleNode(BlockState state) {
        this.blockId = Block.getId(state);
        this.mayWriteFluid = !state.getFluidState().isEmpty();
    }

    BlockSurfaceRuleNode(int blockId) {
        this.blockId = blockId;
        this.mayWriteFluid = !Block.stateById(blockId).getFluidState().isEmpty();
    }

    int blockId() {
        return this.blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.applyBlockState(rawBlockData, this.blockId);
        activeMask.clear();
    }

    @Override
    public int requirements() {
        return 0;
    }

    @Override
    public boolean mayWriteFluid() {
        return this.mayWriteFluid;
    }
}

final class SequenceSurfaceRuleNode implements SurfaceRuleNode {
    private final SurfaceRuleNode[] rules;
    private final int requirements;
    private final boolean mayWriteFluid;

    SequenceSurfaceRuleNode(SurfaceRuleNode[] rules) {
        this.rules = rules;
        int req = 0;
        boolean fluid = false;
        for (SurfaceRuleNode rule : rules) {
            req |= rule.requirements();
            fluid |= rule.mayWriteFluid();
        }
        this.requirements = req;
        this.mayWriteFluid = fluid;
    }

    SurfaceRuleNode[] rules() {
        return this.rules;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        SurfaceRuleNode[] localRules = this.rules;
        for (int i = 0; i < localRules.length; i++) {
            if (activeMask.isEmpty()) {
                SurfaceMetrics.activeMaskEarlyExit();
                return;
            }
            localRules[i].apply(rawBlockData, activeMask, ctx, scratch);
        }
    }

    @Override
    public int requirements() {
        return this.requirements;
    }

    @Override
    public boolean mayWriteFluid() {
        return this.mayWriteFluid;
    }
}

final class TestSurfaceRuleNode implements SurfaceRuleNode {
    private final SurfaceConditionNode condition;
    private final SurfaceRuleNode thenRun;
    private final int requirements;
    private final boolean mayWriteFluid;

    TestSurfaceRuleNode(SurfaceConditionNode condition, SurfaceRuleNode thenRun) {
        this.condition = condition;
        this.thenRun = thenRun;
        this.requirements = condition.requirements() | thenRun.requirements();
        this.mayWriteFluid = thenRun.mayWriteFluid();
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        int mark = scratch.mark();
        Mask4096 matchingMask = scratch.pushMask();
        matchingMask.copyFrom(activeMask);
        this.condition.filter(matchingMask, ctx, scratch);

        if (matchingMask.isEmpty()) {
            scratch.restore(mark);
            return;
        }

        Mask4096 processedBlocks = scratch.pushMask();
        processedBlocks.copyFrom(matchingMask);
        this.thenRun.apply(rawBlockData, matchingMask, ctx, scratch);
        processedBlocks.xor(matchingMask);
        activeMask.andNot(processedBlocks);
        scratch.restore(mark);
    }

    @Override
    public int requirements() {
        return this.requirements;
    }

    @Override
    public boolean mayWriteFluid() {
        return this.mayWriteFluid;
    }
}

final class TestBlockSurfaceRuleNode implements SurfaceRuleNode {
    private final SurfaceConditionNode condition;
    private final int blockId;
    private final boolean mayWriteFluid;

    TestBlockSurfaceRuleNode(SurfaceConditionNode condition, int blockId) {
        this.condition = condition;
        this.blockId = blockId;
        this.mayWriteFluid = !Block.stateById(blockId).getFluidState().isEmpty();
    }

    SurfaceConditionNode condition() {
        return this.condition;
    }

    int blockId() {
        return this.blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        int mark = scratch.mark();
        Mask4096 matchingMask = scratch.pushMask();
        matchingMask.copyFrom(activeMask);
        this.condition.filter(matchingMask, ctx, scratch);

        if (!matchingMask.isEmpty()) {
            matchingMask.applyBlockState(rawBlockData, this.blockId);
            activeMask.andNot(matchingMask);
        }

        scratch.restore(mark);
    }

    @Override
    public int requirements() {
        return this.condition.requirements();
    }

    @Override
    public boolean mayWriteFluid() {
        return this.mayWriteFluid;
    }
}

final class VectorSurfaceRuleBridgeNode implements SurfaceRuleNode {
    private final VectorRule vectorRule;

    VectorSurfaceRuleBridgeNode(VectorRule vectorRule) {
        this.vectorRule = vectorRule;
        SurfaceMetrics.fallbackIsland();
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.toBitSet(scratch.bridgeBitSet);
        this.vectorRule.apply(rawBlockData, scratch.bridgeBitSet, ctx);
        activeMask.fromBitSet(scratch.bridgeBitSet);
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.FALLBACK
                | SurfaceRequirements.BIOME
                | SurfaceRequirements.STONE_DEPTH
                | SurfaceRequirements.WATER
                | SurfaceRequirements.SURFACE_DEPTH
                | SurfaceRequirements.SECONDARY_SURFACE
                | SurfaceRequirements.PRELIMINARY_SURFACE
                | SurfaceRequirements.TEMPERATURE
                | SurfaceRequirements.NOISE
                | SurfaceRequirements.RANDOM
                | SurfaceRequirements.SLOPE;
    }
}

final class CachedSurfaceConditionNode implements SurfaceConditionNode {
    private final SurfaceConditionNode delegate;
    private final int slot;

    CachedSurfaceConditionNode(SurfaceConditionNode delegate, int slot) {
        this.delegate = delegate;
        this.slot = slot;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        Mask4096 cached = scratch.conditionMask(this.slot);
        if (scratch.isConditionValid(this.slot)) {
            SurfaceMetrics.conditionCacheHit();
        } else {
            SurfaceMetrics.conditionCacheMiss();
            cached.fill();
            this.delegate.filter(cached, ctx, scratch);
            scratch.markConditionValid(this.slot);
        }
        activeMask.and(cached);
    }

    @Override
    public int requirements() {
        return this.delegate.requirements();
    }
}

final class VectorSurfaceConditionBridgeNode implements SurfaceConditionNode {
    private final VectorCondition vectorCondition;

    VectorSurfaceConditionBridgeNode(VectorCondition vectorCondition) {
        this.vectorCondition = vectorCondition;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.toBitSet(scratch.bridgeBitSet);
        this.vectorCondition.filter(scratch.bridgeBitSet, ctx);
        activeMask.fromBitSet(scratch.bridgeBitSet);
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.FALLBACK
                | SurfaceRequirements.BIOME
                | SurfaceRequirements.STONE_DEPTH
                | SurfaceRequirements.WATER
                | SurfaceRequirements.SURFACE_DEPTH
                | SurfaceRequirements.SECONDARY_SURFACE
                | SurfaceRequirements.PRELIMINARY_SURFACE
                | SurfaceRequirements.TEMPERATURE
                | SurfaceRequirements.NOISE
                | SurfaceRequirements.RANDOM
                | SurfaceRequirements.SLOPE;
    }
}

final class NotSurfaceConditionNode implements SurfaceConditionNode {
    private final SurfaceConditionNode target;

    NotSurfaceConditionNode(SurfaceConditionNode target) {
        this.target = target;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (activeMask.isEmpty()) {
            return;
        }

        int mark = scratch.mark();
        Mask4096 passedMask = scratch.pushMask();
        passedMask.copyFrom(activeMask);
        this.target.filter(passedMask, ctx, scratch);
        activeMask.xor(passedMask);
        scratch.restore(mark);
    }

    @Override
    public int requirements() {
        return this.target.requirements();
    }
}

final class ConstantSurfaceConditionNode implements SurfaceConditionNode {
    static final ConstantSurfaceConditionNode TRUE = new ConstantSurfaceConditionNode(true);
    static final ConstantSurfaceConditionNode FALSE = new ConstantSurfaceConditionNode(false);

    private final boolean value;

    private ConstantSurfaceConditionNode(boolean value) {
        this.value = value;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (!this.value) {
            activeMask.clear();
        }
    }

    @Override
    public int requirements() {
        return 0;
    }
}

final class BiomeSurfaceConditionNode implements SurfaceConditionNode {
    private final List<ResourceKey<Biome>> targetBiomes;

    BiomeSurfaceConditionNode(List<ResourceKey<Biome>> targetBiomes) {
        this.targetBiomes = targetBiomes;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);

                Holder<Biome> biome = ctx.getBiome(xz);
                boolean matches = false;
                for (int j = 0; j < this.targetBiomes.size(); j++) {
                    if (biome.is(this.targetBiomes.get(j))) {
                        matches = true;
                        break;
                    }
                }

                if (!matches) {
                    activeMask.clearColumn(xz);
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.BIOME;
    }
}

final class HolderSetBiomeSurfaceConditionNode implements SurfaceConditionNode {
    private final HolderSet<Biome> allowedBiomes;

    HolderSetBiomeSurfaceConditionNode(HolderSet<Biome> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                if (!this.allowedBiomes.contains(ctx.getBiome(xz))) {
                    activeMask.clearColumn(xz);
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.BIOME;
    }
}

final class StoneDepthSurfaceConditionNode implements SurfaceConditionNode {
    private final int offset;
    private final boolean addSurfaceDepth;
    private final int secondaryDepthRange;
    private final boolean ceiling;

    StoneDepthSurfaceConditionNode(int offset, boolean addSurfaceDepth, int secondaryDepthRange, CaveSurface surfaceType) {
        this.offset = offset;
        this.addSurfaceDepth = addSurfaceDepth;
        this.secondaryDepthRange = secondaryDepthRange;
        this.ceiling = surfaceType == CaveSurface.CEILING;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                int xzIdx = index & 255;
                int allowedDepth = 1 + this.offset;

                if (this.addSurfaceDepth) {
                    allowedDepth += ctx.surfaceDepths[xzIdx];
                }
                if (this.secondaryDepthRange != 0) {
                    allowedDepth += (int) Mth.map(ctx.secondarySurfaceNoises[xzIdx], -1.0, 1.0, 0.0, this.secondaryDepthRange);
                }

                int currentDepth = this.ceiling ? ctx.stoneDepthBelow[index] : ctx.stoneDepthAbove[index];
                if (currentDepth == 0 || currentDepth > allowedDepth) {
                    activeMask.clear(index);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        int req = SurfaceRequirements.STONE_DEPTH;
        if (this.addSurfaceDepth || this.secondaryDepthRange != 0) {
            req |= SurfaceRequirements.SURFACE_DEPTH;
        }
        if (this.secondaryDepthRange != 0) {
            req |= SurfaceRequirements.SECONDARY_SURFACE;
        }
        return req;
    }
}

final class YSurfaceConditionNode implements SurfaceConditionNode {
    private final VerticalAnchor anchor;
    private final int surfaceDepthMultiplier;
    private final boolean addStoneDepth;

    YSurfaceConditionNode(VerticalAnchor anchor, int surfaceDepthMultiplier, boolean addStoneDepth) {
        this.anchor = anchor;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
        this.addStoneDepth = addStoneDepth;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        int resolvedAnchorY = this.anchor.resolveY(ctx.worldContext);
        if (!this.addStoneDepth) {
            activeMask.computeActiveColumns(scratch.activeColumns);
            for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                long columnWord = scratch.activeColumns[columnWordIndex];
                while (columnWord != 0L) {
                    int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                    int rhs = resolvedAnchorY + (ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier);
                    activeMask.clearColumnBelow(xz, rhs - ctx.sectionStartY);
                    columnWord &= columnWord - 1L;
                }
            }
            return;
        }

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                int globalY = ctx.sectionStartY + (index >> 8);
                int lhs = globalY;
                if (this.addStoneDepth) {
                    lhs += ctx.stoneDepthAbove[index];
                }

                int rhs = resolvedAnchorY + (ctx.surfaceDepths[index & 255] * this.surfaceDepthMultiplier);
                if (lhs < rhs) {
                    activeMask.clear(index);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        int req = SurfaceRequirements.SURFACE_DEPTH;
        if (this.addStoneDepth) {
            req |= SurfaceRequirements.STONE_DEPTH;
        }
        return req;
    }
}

final class NoiseThresholdSurfaceConditionNode implements SurfaceConditionNode {
    private final ResourceKey<NormalNoise.NoiseParameters> noiseKey;
    private final double minThreshold;
    private final double maxThreshold;

    NoiseThresholdSurfaceConditionNode(ResourceKey<NormalNoise.NoiseParameters> noiseKey, double minThreshold, double maxThreshold) {
        this.noiseKey = noiseKey;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        NormalNoise noise = ctx.randomState.getOrCreateNoise(this.noiseKey);
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                int localX = xz & 15;
                int localZ = (xz >> 4) & 15;
                double noiseVal = noise.getValue(ctx.sectionStartX + localX, 0.0, ctx.sectionStartZ + localZ);
                if (noiseVal < this.minThreshold || noiseVal > this.maxThreshold) {
                    activeMask.clearColumn(xz);
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.NOISE;
    }
}

final class VerticalGradientSurfaceConditionNode implements SurfaceConditionNode {
    private final VerticalAnchor trueAtAndBelowY;
    private final VerticalAnchor falseAtAndAboveY;
    private final ResourceLocation randomFactoryName;

    VerticalGradientSurfaceConditionNode(VerticalAnchor trueAtAndBelowY, VerticalAnchor falseAtAndAboveY, ResourceLocation randomFactoryName) {
        this.trueAtAndBelowY = trueAtAndBelowY;
        this.falseAtAndAboveY = falseAtAndAboveY;
        this.randomFactoryName = randomFactoryName;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        int trueY = this.trueAtAndBelowY.resolveY(ctx.worldContext);
        int falseY = this.falseAtAndAboveY.resolveY(ctx.worldContext);
        PositionalRandomFactory randomFactory = ctx.randomState.getOrCreateRandomFactory(this.randomFactoryName);

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                int localY = index >> 8;
                int globalY = ctx.sectionStartY + localY;

                if (globalY >= falseY) {
                    activeMask.clear(index);
                } else if (globalY > trueY) {
                    double chance = Mth.map(globalY, trueY, falseY, 1.0, 0.0);
                    int localX = index & 15;
                    int localZ = (index >> 4) & 15;
                    RandomSource random = randomFactory.at(ctx.sectionStartX + localX, globalY, ctx.sectionStartZ + localZ);
                    if (random.nextFloat() >= chance) {
                        activeMask.clear(index);
                    }
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.RANDOM;
    }
}

final class AbovePreliminarySurfaceConditionNode implements SurfaceConditionNode {
    static final AbovePreliminarySurfaceConditionNode INSTANCE = new AbovePreliminarySurfaceConditionNode();

    private AbovePreliminarySurfaceConditionNode() {
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                activeMask.clearColumnBelow(xz, ctx.minSurfaceLevels[xz] - ctx.sectionStartY);
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.PRELIMINARY_SURFACE;
    }
}

final class WaterSurfaceConditionNode implements SurfaceConditionNode {
    private final int offset;
    private final int surfaceDepthMultiplier;
    private final boolean addStoneDepth;

    WaterSurfaceConditionNode(int offset, int surfaceDepthMultiplier, boolean addStoneDepth) {
        this.offset = offset;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
        this.addStoneDepth = addStoneDepth;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (!this.addStoneDepth) {
            activeMask.computeActiveColumns(scratch.activeColumns);
            for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
                long columnWord = scratch.activeColumns[columnWordIndex];
                while (columnWord != 0L) {
                    int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                int waterHeight = ctx.waterHeights[xz];
                if (waterHeight == Integer.MIN_VALUE) {
                        columnWord &= columnWord - 1L;
                    continue;
                }
                int rhs = waterHeight + this.offset + (ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier);
                activeMask.clearColumnBelow(xz, rhs - ctx.sectionStartY);
                    columnWord &= columnWord - 1L;
                }
            }
            return;
        }

        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                int xzIdx = index & 255;
                int waterHeight = ctx.waterHeights[xzIdx];

                if (waterHeight != Integer.MIN_VALUE) {
                    int lhs = ctx.sectionStartY + (index >> 8);
                    if (this.addStoneDepth) {
                        lhs += ctx.stoneDepthAbove[index];
                    }
                    int rhs = waterHeight + this.offset + (ctx.surfaceDepths[xzIdx] * this.surfaceDepthMultiplier);
                    if (lhs < rhs) {
                        activeMask.clear(index);
                    }
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        int req = SurfaceRequirements.WATER | SurfaceRequirements.SURFACE_DEPTH;
        if (this.addStoneDepth) {
            req |= SurfaceRequirements.STONE_DEPTH;
        }
        return req;
    }
}

final class TemperatureSurfaceConditionNode implements SurfaceConditionNode {
    static final TemperatureSurfaceConditionNode INSTANCE = new TemperatureSurfaceConditionNode();

    private TemperatureSurfaceConditionNode() {
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        long[] words = activeMask.words();
        for (int wordIndex = 0; wordIndex < Mask4096.WORD_COUNT; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int index = (wordIndex << 6) + bit;
                Holder<Biome> biome = ctx.surfaceBiomes[index & 255];
                int localX = index & 15;
                int localZ = (index >> 4) & 15;
                int localY = index >> 8;

                scratch.mutablePos.set(ctx.sectionStartX + localX, ctx.sectionStartY + localY, ctx.sectionStartZ + localZ);
                if (!biome.value().coldEnoughToSnow(scratch.mutablePos)) {
                    activeMask.clear(index);
                }
                word &= word - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.TEMPERATURE | SurfaceRequirements.BIOME;
    }
}

final class SteepSurfaceConditionNode implements SurfaceConditionNode {
    static final SteepSurfaceConditionNode INSTANCE = new SteepSurfaceConditionNode();

    private SteepSurfaceConditionNode() {
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);

            int localX = xz & 15;
            int localZ = (xz >> 4) & 15;
            int north = Math.max(localZ - 1, 0);
            int south = Math.min(localZ + 1, 15);
            int height1 = ctx.surfaceHeights[localX | (north << 4)];
            int height2 = ctx.surfaceHeights[localX | (south << 4)];

            boolean steep = height2 >= height1 + 4;
            if (!steep) {
                int west = Math.max(localX - 1, 0);
                int east = Math.min(localX + 1, 15);
                int height3 = ctx.surfaceHeights[west | (localZ << 4)];
                int height4 = ctx.surfaceHeights[east | (localZ << 4)];
                steep = height3 >= height4 + 4;
            }

            if (!steep) {
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

final class HoleSurfaceConditionNode implements SurfaceConditionNode {
    static final HoleSurfaceConditionNode INSTANCE = new HoleSurfaceConditionNode();

    private HoleSurfaceConditionNode() {
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.activeColumns);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = scratch.activeColumns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                if (ctx.surfaceDepths[xz] > 0) {
                    activeMask.clearColumn(xz);
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public int requirements() {
        return SurfaceRequirements.SURFACE_DEPTH;
    }
}

final class AllOfSurfaceConditionNode implements SurfaceConditionNode {
    private final SurfaceConditionNode[] conditions;
    private final int requirements;

    AllOfSurfaceConditionNode(SurfaceConditionNode[] conditions) {
        this.conditions = conditions;
        int req = 0;
        for (SurfaceConditionNode condition : conditions) {
            req |= condition.requirements();
        }
        this.requirements = req;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (SurfaceConditionNode condition : this.conditions) {
            if (activeMask.isEmpty()) {
                return;
            }
            condition.filter(activeMask, ctx, scratch);
        }
    }

    @Override
    public int requirements() {
        return this.requirements;
    }
}

final class AnyOfSurfaceConditionNode implements SurfaceConditionNode {
    private final SurfaceConditionNode[] conditions;
    private final int requirements;

    AnyOfSurfaceConditionNode(SurfaceConditionNode[] conditions) {
        this.conditions = conditions;
        int req = 0;
        for (SurfaceConditionNode condition : conditions) {
            req |= condition.requirements();
        }
        this.requirements = req;
    }

    @Override
    public void filter(Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (this.conditions.length == 0 || activeMask.isEmpty()) {
            activeMask.clear();
            return;
        }

        int mark = scratch.mark();
        Mask4096 finalPassedMask = scratch.pushMask();
        Mask4096 remainingMask = scratch.pushMask();
        remainingMask.copyFrom(activeMask);

        for (SurfaceConditionNode condition : this.conditions) {
            if (remainingMask.isEmpty()) {
                break;
            }
            Mask4096 testMask = scratch.pushMask();
            testMask.copyFrom(remainingMask);
            condition.filter(testMask, ctx, scratch);
            finalPassedMask.or(testMask);
            remainingMask.andNot(testMask);
        }

        activeMask.and(finalPassedMask);
        scratch.restore(mark);
    }

    @Override
    public int requirements() {
        return this.requirements;
    }
}
