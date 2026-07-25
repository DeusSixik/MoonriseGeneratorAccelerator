package dev.sixik.generator_accelerator.common.density.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellFillStats;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcZeroCellFillAccess;
import dev.sixik.generator_accelerator.common.noise.NoiseChunkTimingStats;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public abstract class MixinDensityFunctions$Ap2 implements DfcCellFillAccess, DfcCellFillFastPath {
    @Unique
    private boolean ga$cellFillPathResolved;
    @Unique
    private boolean ga$hasFastCellFillPath;
    @Unique
    private DfcCellFillAccess ga$leftCellFill;
    @Unique
    private DfcCellFillAccess ga$rightCellFill;
    @Unique
    private boolean ga$leftCellFillZero;
    @Unique
    private boolean ga$rightCellFillZero;

    @Shadow
    public abstract DensityFunctions.TwoArgumentSimpleFunction.Type type();

    @Shadow
    public abstract DensityFunction argument1();

    @Shadow
    public abstract DensityFunction argument2();

    @Override
    public boolean dfc$hasFastCellFillPath() {
        if (!this.ga$cellFillPathResolved) {
            this.ga$resolveCellFillPath();
        }
        return this.ga$hasFastCellFillPath;
    }

    @Override
    public void dfc$fillCell(double[] out, NoiseChunk chunk) {
        if (!this.ga$cellFillPathResolved) {
            this.ga$resolveCellFillPath();
        }
        if (!this.ga$hasFastCellFillPath) {
            ((DensityFunction) (Object) this).fillArray(out, chunk);
            return;
        }
        final boolean cellFillStats = DfcCellFillStats.ENABLED;
        final boolean timingStages = NoiseChunkTimingStats.stageTimingEnabled();
        if (this.ga$leftCellFillZero) {
            if (this.ga$rightCellFillZero) {
                Arrays.fill(out, 0.0D);
                ga$finishCellState(chunk, out.length);
                return;
            }
            if (cellFillStats) {
                DfcCellFillStats.recordCellFill(this.ga$rightCellFill, this.argument2());
            }
            long secondaryStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
            this.ga$rightCellFill.dfc$fillCell(out, chunk);
            if (timingStages) {
                NoiseChunkTimingStats.recordAp2Secondary(secondaryStart);
            }
            return;
        }
        if (cellFillStats) {
            DfcCellFillStats.recordCellFill(this.ga$leftCellFill, this.argument1());
        }
        long primaryStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
        this.ga$leftCellFill.dfc$fillCell(out, chunk);
        if (timingStages) {
            NoiseChunkTimingStats.recordAp2Primary(primaryStart);
        }
        if (this.ga$rightCellFillZero) {
            if (timingStages) {
                NoiseChunkTimingStats.recordAp2ZeroSecondarySkip();
            }
            return;
        }
        if (cellFillStats) {
            DfcCellFillStats.recordCellFill(this.ga$rightCellFill, this.argument2());
        }
        long secondaryStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
        this.ga$rightCellFill.dfc$accumulateCell(out, chunk);
        if (timingStages) {
            NoiseChunkTimingStats.recordAp2Secondary(secondaryStart);
        }
    }

    @Override
    public void dfc$accumulateCell(double[] out, NoiseChunk chunk) {
        if (!this.ga$cellFillPathResolved) {
            this.ga$resolveCellFillPath();
        }
        if (!this.ga$hasFastCellFillPath) {
            DfcCellFillAccess.super.dfc$accumulateCell(out, chunk);
            return;
        }
        final boolean cellFillStats = DfcCellFillStats.ENABLED;
        final boolean timingStages = NoiseChunkTimingStats.stageTimingEnabled();
        if (!this.ga$leftCellFillZero) {
            if (cellFillStats) {
                DfcCellFillStats.recordCellFill(this.ga$leftCellFill, this.argument1());
            }
            long primaryStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
            this.ga$leftCellFill.dfc$accumulateCell(out, chunk);
            if (timingStages) {
                NoiseChunkTimingStats.recordAp2Primary(primaryStart);
            }
        }
        if (!this.ga$rightCellFillZero) {
            if (cellFillStats) {
                DfcCellFillStats.recordCellFill(this.ga$rightCellFill, this.argument2());
            }
            long secondaryStart = timingStages ? NoiseChunkTimingStats.startStage() : 0L;
            this.ga$rightCellFill.dfc$accumulateCell(out, chunk);
            if (timingStages) {
                NoiseChunkTimingStats.recordAp2Secondary(secondaryStart);
            }
        } else {
            if (timingStages) {
                NoiseChunkTimingStats.recordAp2ZeroSecondarySkip();
            }
        }
    }

    @Unique
    private void ga$resolveCellFillPath() {
        if (this.ga$cellFillPathResolved) {
            return;
        }
        if (this.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            DfcCellFillAccess left = DfcCellFillFastPath.asFastPath(this.argument1());
            DfcCellFillAccess right = DfcCellFillFastPath.asFastPath(this.argument2());
            if (left != null && right != null) {
                this.ga$leftCellFill = left;
                this.ga$rightCellFill = right;
                this.ga$leftCellFillZero = left instanceof DfcZeroCellFillAccess;
                this.ga$rightCellFillZero = right instanceof DfcZeroCellFillAccess;
                this.ga$hasFastCellFillPath = true;
            }
        }
        this.ga$cellFillPathResolved = true;
    }

    @Unique
    private static void ga$finishCellState(NoiseChunk chunk, int valueCount) {
        chunk.inCellX = chunk.cellWidth - 1;
        chunk.inCellY = 0;
        chunk.inCellZ = chunk.cellWidth - 1;
        chunk.arrayIndex = valueCount;
    }
}
