package dev.sixik.generator_accelerator.common.surface.compiler;

import dev.sixik.generator_accelerator.common.surface.compiler.mask.Mask4096;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.Arrays;
import java.util.List;

interface SurfaceProgramStep {
    void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch);
}

final class BlockProgramStep implements SurfaceProgramStep {
    private final int blockId;

    BlockProgramStep(int blockId) {
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.applyBlockState(rawBlockData, this.blockId);
        activeMask.clear();
    }
}

final class RuleProgramStep implements SurfaceProgramStep {
    private final SurfaceRuleNode rule;

    RuleProgramStep(SurfaceRuleNode rule) {
        this.rule = rule;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        this.rule.apply(rawBlockData, activeMask, ctx, scratch);
    }
}

final class MaskTestBlockProgramStep implements SurfaceProgramStep {
    private final SurfaceConditionNode condition;
    private final int blockId;

    MaskTestBlockProgramStep(SurfaceConditionNode condition, int blockId) {
        this.condition = condition;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        int mark = scratch.mark();
        Mask4096 matchingMask = scratch.pushMaskForOverwrite();
        matchingMask.copyFrom(activeMask);
        this.condition.filter(matchingMask, ctx, scratch);

        if (!matchingMask.isEmpty()) {
            matchingMask.applyBlockState(rawBlockData, this.blockId);
            activeMask.andNot(matchingMask);
        }

        scratch.restore(mark);
    }
}

final class IntervalTestBlockProgramStep implements SurfaceProgramStep {
    private final IntervalConditionPlan condition;
    private final int blockId;
    private final int cacheSlot;

    IntervalTestBlockProgramStep(IntervalConditionPlan condition, int blockId) {
        this(condition, blockId, -1);
    }

    IntervalTestBlockProgramStep(IntervalConditionPlan condition, int blockId, int cacheSlot) {
        this.condition = condition;
        this.blockId = blockId;
        this.cacheSlot = cacheSlot;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (this.cacheSlot < 0 && this.condition.applyDirect(rawBlockData, this.blockId, activeMask, ctx, scratch)) {
            return;
        }
        activeMask.applyBlockStateAndClearAbove(rawBlockData, this.blockId, intervalMinY(ctx, scratch), scratch.activeColumns);
    }

    private int[] intervalMinY(VectorChunkContext ctx, SurfaceScratch scratch) {
        if (this.cacheSlot < 0) {
            int[] minY = scratch.intervalMinY;
            Arrays.fill(minY, 0);
            this.condition.applyMinY(minY, ctx);
            return minY;
        }

        int[] cached = scratch.intervalMinYCache(this.cacheSlot);
        if (!scratch.isIntervalConditionValid(this.cacheSlot)) {
            Arrays.fill(cached, 0);
            this.condition.applyMinY(cached, ctx);
            scratch.markIntervalConditionValid(this.cacheSlot);
        }
        return cached;
    }
}

final class MinYTestBlockProgramStep implements SurfaceProgramStep {
    private final int minLocalY;
    private final int blockId;

    MinYTestBlockProgramStep(int minLocalY, int blockId) {
        this.minLocalY = minLocalY;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.applyBlockStateAndClearAbove(rawBlockData, this.blockId, this.minLocalY);
    }
}

final class AnchorYTestBlockProgramStep implements SurfaceProgramStep {
    private final VerticalAnchor anchor;
    private final int blockId;

    AnchorYTestBlockProgramStep(VerticalAnchor anchor, int blockId) {
        this.anchor = anchor;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.applyBlockStateAndClearAbove(rawBlockData, this.blockId, this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY);
    }
}

final class ColumnIntervalTestBlockProgramStep implements SurfaceProgramStep {
    private final ColumnConditionPlan columnCondition;
    private final IntervalConditionPlan intervalCondition;
    private final int blockId;
    private final int intervalCacheSlot;

    ColumnIntervalTestBlockProgramStep(ColumnConditionPlan columnCondition, IntervalConditionPlan intervalCondition, int blockId) {
        this(columnCondition, intervalCondition, blockId, -1);
    }

    ColumnIntervalTestBlockProgramStep(ColumnConditionPlan columnCondition, IntervalConditionPlan intervalCondition, int blockId, int intervalCacheSlot) {
        this.columnCondition = columnCondition;
        this.intervalCondition = intervalCondition;
        this.blockId = blockId;
        this.intervalCacheSlot = intervalCacheSlot;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.candidateColumns);
        this.columnCondition.filterColumns(scratch.candidateColumns, ctx, scratch);
        long[] columns = scratch.candidateColumns;
        if (columns[0] == 0L && columns[1] == 0L && columns[2] == 0L && columns[3] == 0L) {
            return;
        }

        if (this.intervalCacheSlot < 0 && this.intervalCondition.applyDirect(rawBlockData, this.blockId, activeMask, ctx, scratch, columns)) {
            return;
        }

        activeMask.applyBlockStateAndClearAbove(rawBlockData, this.blockId, intervalMinY(ctx, scratch), columns, null);
    }

    private int[] intervalMinY(VectorChunkContext ctx, SurfaceScratch scratch) {
        if (this.intervalCacheSlot < 0) {
            int[] minY = scratch.intervalMinY;
            Arrays.fill(minY, 0);
            this.intervalCondition.applyMinY(minY, ctx);
            return minY;
        }

        int[] cached = scratch.intervalMinYCache(this.intervalCacheSlot);
        if (!scratch.isIntervalConditionValid(this.intervalCacheSlot)) {
            Arrays.fill(cached, 0);
            this.intervalCondition.applyMinY(cached, ctx);
            scratch.markIntervalConditionValid(this.intervalCacheSlot);
        }
        return cached;
    }
}

final class ColumnTestBlockProgramStep implements SurfaceProgramStep {
    private final ColumnConditionPlan condition;
    private final int blockId;

    ColumnTestBlockProgramStep(ColumnConditionPlan condition, int blockId) {
        this.condition = condition;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.candidateColumns);
        this.condition.filterColumns(scratch.candidateColumns, ctx, scratch);
        long[] columns = scratch.candidateColumns;
        if (columns[0] == 0L && columns[1] == 0L && columns[2] == 0L && columns[3] == 0L) {
            return;
        }
        activeMask.applyBlockStateAndClearColumns(rawBlockData, this.blockId, columns, null);
    }
}

final class ColumnMinYTestBlockProgramStep implements SurfaceProgramStep {
    private final ColumnConditionPlan condition;
    private final int minLocalY;
    private final int blockId;

    ColumnMinYTestBlockProgramStep(ColumnConditionPlan condition, int minLocalY, int blockId) {
        this.condition = condition;
        this.minLocalY = minLocalY;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.candidateColumns);
        this.condition.filterColumns(scratch.candidateColumns, ctx, scratch);
        long[] columns = scratch.candidateColumns;
        if (columns[0] == 0L && columns[1] == 0L && columns[2] == 0L && columns[3] == 0L) {
            return;
        }
        activeMask.applyBlockStateAndClearAbove(rawBlockData, this.blockId, this.minLocalY, columns, null);
    }
}

final class ColumnAnchorYTestBlockProgramStep implements SurfaceProgramStep {
    private final ColumnConditionPlan condition;
    private final VerticalAnchor anchor;
    private final int blockId;

    ColumnAnchorYTestBlockProgramStep(ColumnConditionPlan condition, VerticalAnchor anchor, int blockId) {
        this.condition = condition;
        this.anchor = anchor;
        this.blockId = blockId;
    }

    @Override
    public void apply(int[] rawBlockData, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.computeActiveColumns(scratch.candidateColumns);
        this.condition.filterColumns(scratch.candidateColumns, ctx, scratch);
        long[] columns = scratch.candidateColumns;
        if (columns[0] == 0L && columns[1] == 0L && columns[2] == 0L && columns[3] == 0L) {
            return;
        }
        activeMask.applyBlockStateAndClearAbove(
                rawBlockData,
                this.blockId,
                this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY,
                columns,
                null
        );
    }
}

interface ColumnConditionPlan {
    void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch);
}

final class CachedColumnConditionPlan implements ColumnConditionPlan {
    private final ColumnConditionPlan target;
    private final int cacheSlot;

    CachedColumnConditionPlan(ColumnConditionPlan target, int cacheSlot) {
        this.target = target;
        this.cacheSlot = cacheSlot;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        long[] cached = scratch.columnConditionMask(this.cacheSlot);
        if (!scratch.isColumnConditionValid(this.cacheSlot)) {
            cached[0] = -1L;
            cached[1] = -1L;
            cached[2] = -1L;
            cached[3] = -1L;
            this.target.filterColumns(cached, ctx, scratch);
            scratch.markColumnConditionValid(this.cacheSlot);
        }
        columns[0] &= cached[0];
        columns[1] &= cached[1];
        columns[2] &= cached[2];
        columns[3] &= cached[3];
    }
}

final class TrueColumnConditionPlan implements ColumnConditionPlan {
    static final TrueColumnConditionPlan INSTANCE = new TrueColumnConditionPlan();

    private TrueColumnConditionPlan() {
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
    }
}

final class FalseColumnConditionPlan implements ColumnConditionPlan {
    static final FalseColumnConditionPlan INSTANCE = new FalseColumnConditionPlan();

    private FalseColumnConditionPlan() {
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        columns[0] = 0L;
        columns[1] = 0L;
        columns[2] = 0L;
        columns[3] = 0L;
    }
}

final class AllOfColumnConditionPlan implements ColumnConditionPlan {
    private final ColumnConditionPlan[] conditions;

    AllOfColumnConditionPlan(ColumnConditionPlan[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (ColumnConditionPlan condition : this.conditions) {
            condition.filterColumns(columns, ctx, scratch);
            if (columns[0] == 0L && columns[1] == 0L && columns[2] == 0L && columns[3] == 0L) {
                return;
            }
        }
    }
}

final class AnyOfColumnConditionPlan implements ColumnConditionPlan {
    private final ColumnConditionPlan[] conditions;

    AnyOfColumnConditionPlan(ColumnConditionPlan[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        long base0 = columns[0];
        long base1 = columns[1];
        long base2 = columns[2];
        long base3 = columns[3];
        long out0 = 0L;
        long out1 = 0L;
        long out2 = 0L;
        long out3 = 0L;

        for (ColumnConditionPlan condition : this.conditions) {
            long[] probe = scratch.activeColumns;
            probe[0] = base0 & ~out0;
            probe[1] = base1 & ~out1;
            probe[2] = base2 & ~out2;
            probe[3] = base3 & ~out3;
            if (probe[0] == 0L && probe[1] == 0L && probe[2] == 0L && probe[3] == 0L) {
                break;
            }
            condition.filterColumns(probe, ctx, scratch);
            out0 |= probe[0];
            out1 |= probe[1];
            out2 |= probe[2];
            out3 |= probe[3];
        }

        columns[0] = out0;
        columns[1] = out1;
        columns[2] = out2;
        columns[3] = out3;
    }
}

final class NotColumnConditionPlan implements ColumnConditionPlan {
    private final ColumnConditionPlan target;

    NotColumnConditionPlan(ColumnConditionPlan target) {
        this.target = target;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        long base0 = columns[0];
        long base1 = columns[1];
        long base2 = columns[2];
        long base3 = columns[3];
        this.target.filterColumns(columns, ctx, scratch);
        columns[0] = base0 & ~columns[0];
        columns[1] = base1 & ~columns[1];
        columns[2] = base2 & ~columns[2];
        columns[3] = base3 & ~columns[3];
    }
}

final class BiomeColumnConditionPlan implements ColumnConditionPlan {
    private final List<ResourceKey<Biome>> targetBiomes;

    BiomeColumnConditionPlan(List<ResourceKey<Biome>> targetBiomes) {
        this.targetBiomes = targetBiomes;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            long kept = columnWord;
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                Holder<Biome> biome = ctx.getBiome(xz);
                boolean matches = false;
                for (int i = 0; i < this.targetBiomes.size(); i++) {
                    if (biome.is(this.targetBiomes.get(i))) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    kept &= ~(1L << bit);
                }
                columnWord &= columnWord - 1L;
            }
            columns[columnWordIndex] = kept;
        }
    }
}

final class HolderSetBiomeColumnConditionPlan implements ColumnConditionPlan {
    private final HolderSet<Biome> allowedBiomes;

    HolderSetBiomeColumnConditionPlan(HolderSet<Biome> allowedBiomes) {
        this.allowedBiomes = allowedBiomes;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            long kept = columnWord;
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                if (!this.allowedBiomes.contains(ctx.getBiome(xz))) {
                    kept &= ~(1L << bit);
                }
                columnWord &= columnWord - 1L;
            }
            columns[columnWordIndex] = kept;
        }
    }
}

final class NoiseThresholdColumnConditionPlan implements ColumnConditionPlan {
    private final ResourceKey<NormalNoise.NoiseParameters> noiseKey;
    private final double minThreshold;
    private final double maxThreshold;

    NoiseThresholdColumnConditionPlan(ResourceKey<NormalNoise.NoiseParameters> noiseKey, double minThreshold, double maxThreshold) {
        this.noiseKey = noiseKey;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        int stamp = ctx.nextColumnScratchStamp();
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            long kept = columnWord;
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                int localX = xz & 15;
                int localZ = (xz >> 4) & 15;
                double value = ctx.sampleNoiseColumn(this.noiseKey, localX, localZ, xz, stamp);
                if (value < this.minThreshold || value > this.maxThreshold) {
                    kept &= ~(1L << bit);
                }
                columnWord &= columnWord - 1L;
            }
            columns[columnWordIndex] = kept;
        }
    }
}

final class HoleColumnConditionPlan implements ColumnConditionPlan {
    static final HoleColumnConditionPlan INSTANCE = new HoleColumnConditionPlan();

    private HoleColumnConditionPlan() {
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            long kept = columnWord;
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                if (ctx.surfaceDepths[xz] > 0) {
                    kept &= ~(1L << bit);
                }
                columnWord &= columnWord - 1L;
            }
            columns[columnWordIndex] = kept;
        }
    }
}

final class SteepColumnConditionPlan implements ColumnConditionPlan {
    static final SteepColumnConditionPlan INSTANCE = new SteepColumnConditionPlan();

    private SteepColumnConditionPlan() {
    }

    @Override
    public void filterColumns(long[] columns, VectorChunkContext ctx, SurfaceScratch scratch) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            long kept = columnWord;
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
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
                    kept &= ~(1L << bit);
                }
                columnWord &= columnWord - 1L;
            }
            columns[columnWordIndex] = kept;
        }
    }
}

interface IntervalConditionPlan {
    void applyMinY(int[] minY, VectorChunkContext ctx);

    default void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        applyMinY(minY, ctx);
    }

    default boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        return false;
    }

    default boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        return false;
    }
}

final class TrueIntervalConditionPlan implements IntervalConditionPlan {
    static final TrueIntervalConditionPlan INSTANCE = new TrueIntervalConditionPlan();

    private TrueIntervalConditionPlan() {
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        activeMask.applyBlockState(rawBlockData, blockId);
        activeMask.clear();
        return true;
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        activeMask.applyBlockStateAndClearColumns(rawBlockData, blockId, columns, null);
        return true;
    }
}

final class FalseIntervalConditionPlan implements IntervalConditionPlan {
    static final FalseIntervalConditionPlan INSTANCE = new FalseIntervalConditionPlan();

    private FalseIntervalConditionPlan() {
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
        Arrays.fill(minY, 17);
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                minY[(columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord)] = 17;
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        return true;
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        return true;
    }
}

final class AllOfIntervalConditionPlan implements IntervalConditionPlan {
    private final IntervalConditionPlan[] conditions;

    AllOfIntervalConditionPlan(IntervalConditionPlan[] conditions) {
        this.conditions = conditions;
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
        for (IntervalConditionPlan condition : this.conditions) {
            condition.applyMinY(minY, ctx);
        }
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        for (IntervalConditionPlan condition : this.conditions) {
            condition.applyMinY(minY, ctx, columns);
        }
    }
}

final class YIntervalConditionPlan implements IntervalConditionPlan {
    final VerticalAnchor anchor;
    final int surfaceDepthMultiplier;

    YIntervalConditionPlan(VerticalAnchor anchor, int surfaceDepthMultiplier) {
        this.anchor = anchor;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
        int resolvedAnchorY = this.anchor.resolveY(ctx.worldContext);
        for (int xz = 0; xz < 256; xz++) {
            int localMinY = resolvedAnchorY + ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier - ctx.sectionStartY;
            if (localMinY > minY[xz]) {
                minY[xz] = localMinY;
            }
        }
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        int resolvedAnchorY = this.anchor.resolveY(ctx.worldContext);
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                int localMinY = resolvedAnchorY + ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier - ctx.sectionStartY;
                if (localMinY > minY[xz]) {
                    minY[xz] = localMinY;
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        if (this.surfaceDepthMultiplier == 0) {
            activeMask.applyBlockStateAndClearAbove(rawBlockData, blockId, this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY);
            return true;
        }

        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int baseLocalY = this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY;
        int[] surfaceDepths = ctx.surfaceDepths;
        int multiplier = this.surfaceDepthMultiplier;
        for (int xz = 0; xz < 256; xz++) {
            IntervalLayerMaskBuilder.addColumn(layerMasks, baseLocalY + surfaceDepths[xz] * multiplier, xz);
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        if (this.surfaceDepthMultiplier == 0) {
            activeMask.applyBlockStateAndClearAbove(rawBlockData, blockId, this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY, columns, null);
            return true;
        }

        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int baseLocalY = this.anchor.resolveY(ctx.worldContext) - ctx.sectionStartY;
        int[] surfaceDepths = ctx.surfaceDepths;
        int multiplier = this.surfaceDepthMultiplier;
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                IntervalLayerMaskBuilder.addColumn(layerMasks, baseLocalY + surfaceDepths[xz] * multiplier, xz);
                columnWord &= columnWord - 1L;
            }
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }
}

final class AbovePreliminaryIntervalConditionPlan implements IntervalConditionPlan {
    static final AbovePreliminaryIntervalConditionPlan INSTANCE = new AbovePreliminaryIntervalConditionPlan();

    private AbovePreliminaryIntervalConditionPlan() {
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
        for (int xz = 0; xz < 256; xz++) {
            int localMinY = ctx.minSurfaceLevels[xz] - ctx.sectionStartY;
            if (localMinY > minY[xz]) {
                minY[xz] = localMinY;
            }
        }
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                int localMinY = ctx.minSurfaceLevels[xz] - ctx.sectionStartY;
                if (localMinY > minY[xz]) {
                    minY[xz] = localMinY;
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int[] minSurfaceLevels = ctx.minSurfaceLevels;
        int sectionStartY = ctx.sectionStartY;
        for (int xz = 0; xz < 256; xz++) {
            IntervalLayerMaskBuilder.addColumn(layerMasks, minSurfaceLevels[xz] - sectionStartY, xz);
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int[] minSurfaceLevels = ctx.minSurfaceLevels;
        int sectionStartY = ctx.sectionStartY;
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                IntervalLayerMaskBuilder.addColumn(layerMasks, minSurfaceLevels[xz] - sectionStartY, xz);
                columnWord &= columnWord - 1L;
            }
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }
}

final class WaterIntervalConditionPlan implements IntervalConditionPlan {
    final int offset;
    final int surfaceDepthMultiplier;

    WaterIntervalConditionPlan(int offset, int surfaceDepthMultiplier) {
        this.offset = offset;
        this.surfaceDepthMultiplier = surfaceDepthMultiplier;
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx) {
        for (int xz = 0; xz < 256; xz++) {
            int waterHeight = ctx.waterHeights[xz];
            if (waterHeight == Integer.MIN_VALUE) {
                continue;
            }
            int localMinY = waterHeight + this.offset + ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier - ctx.sectionStartY;
            if (localMinY > minY[xz]) {
                minY[xz] = localMinY;
            }
        }
    }

    @Override
    public void applyMinY(int[] minY, VectorChunkContext ctx, long[] columns) {
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int xz = (columnWordIndex << 6) + Long.numberOfTrailingZeros(columnWord);
                int waterHeight = ctx.waterHeights[xz];
                if (waterHeight != Integer.MIN_VALUE) {
                    int localMinY = waterHeight + this.offset + ctx.surfaceDepths[xz] * this.surfaceDepthMultiplier - ctx.sectionStartY;
                    if (localMinY > minY[xz]) {
                        minY[xz] = localMinY;
                    }
                }
                columnWord &= columnWord - 1L;
            }
        }
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch) {
        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int[] waterHeights = ctx.waterHeights;
        int[] surfaceDepths = ctx.surfaceDepths;
        int sectionStartY = ctx.sectionStartY;
        int offsetValue = this.offset;
        int multiplier = this.surfaceDepthMultiplier;
        for (int xz = 0; xz < 256; xz++) {
            int waterHeight = waterHeights[xz];
            int minY = waterHeight == Integer.MIN_VALUE
                    ? 0
                    : waterHeight + offsetValue + surfaceDepths[xz] * multiplier - sectionStartY;
            IntervalLayerMaskBuilder.addColumn(layerMasks, minY, xz);
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }

    @Override
    public boolean applyDirect(int[] rawBlockData, int blockId, Mask4096 activeMask, VectorChunkContext ctx, SurfaceScratch scratch, long[] columns) {
        long[] layerMasks = scratch.layeredColumns;
        Arrays.fill(layerMasks, 0L);
        int[] waterHeights = ctx.waterHeights;
        int[] surfaceDepths = ctx.surfaceDepths;
        int sectionStartY = ctx.sectionStartY;
        int offsetValue = this.offset;
        int multiplier = this.surfaceDepthMultiplier;
        for (int columnWordIndex = 0; columnWordIndex < 4; columnWordIndex++) {
            long columnWord = columns[columnWordIndex];
            while (columnWord != 0L) {
                int bit = Long.numberOfTrailingZeros(columnWord);
                int xz = (columnWordIndex << 6) + bit;
                int waterHeight = waterHeights[xz];
                int minY = waterHeight == Integer.MIN_VALUE
                        ? 0
                        : waterHeight + offsetValue + surfaceDepths[xz] * multiplier - sectionStartY;
                IntervalLayerMaskBuilder.addColumn(layerMasks, minY, xz);
                columnWord &= columnWord - 1L;
            }
        }
        IntervalLayerMaskBuilder.accumulate(layerMasks);
        activeMask.applyBlockStateAndClearLayered(rawBlockData, blockId, layerMasks);
        return true;
    }
}

final class IntervalLayerMaskBuilder {
    private IntervalLayerMaskBuilder() {
    }

    static void addColumn(long[] layerMasks, int minY, int xz) {
        if (minY < 0) {
            minY = 0;
        }
        if (minY >= 16) {
            return;
        }
        layerMasks[(minY << 2) + (xz >>> 6)] |= 1L << (xz & 63);
    }

    static void accumulate(long[] layerMasks) {
        for (int y = 1; y < 16; y++) {
            int base = y << 2;
            int prevBase = base - 4;
            layerMasks[base] |= layerMasks[prevBase];
            layerMasks[base + 1] |= layerMasks[prevBase + 1];
            layerMasks[base + 2] |= layerMasks[prevBase + 2];
            layerMasks[base + 3] |= layerMasks[prevBase + 3];
        }
    }
}
