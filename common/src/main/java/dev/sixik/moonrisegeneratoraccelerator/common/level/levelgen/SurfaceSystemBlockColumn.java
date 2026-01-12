package dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.NotNull;

public class SurfaceSystemBlockColumn implements BlockColumn {

    private ChunkAccess chunk;
    private BlockPos.MutableBlockPos blockPos;

    public SurfaceSystemBlockColumn() {
        this(null, null);
    }

    public SurfaceSystemBlockColumn(ChunkAccess chunk, BlockPos.MutableBlockPos blockPos) {
        this.blockPos = blockPos;
        this.chunk = chunk;
    }

    public void preInit(ChunkAccess chunk, BlockPos.MutableBlockPos blockPos) {
        this.chunk = chunk;
        this.blockPos = blockPos;
    }

    public void preInit() {
        this.chunk = null;
        this.blockPos = null;
    }

    @Override
    public @NotNull BlockState getBlock(int pos) {
        return chunk.getBlockState(blockPos.setY(pos));
    }

    @Override
    public void setBlock(int pos, BlockState state) {
        final LevelHeightAccessor levelHeightAccessor = chunk.getHeightAccessorForGeneration();
        if (pos >= levelHeightAccessor.getMinBuildHeight() && pos < levelHeightAccessor.getMaxBuildHeight()) {
            chunk.setBlockState(blockPos.setY(pos), state, false);
            if (!state.getFluidState().isEmpty()) {
                chunk.markPosForPostprocessing(blockPos);
            }
        }
    }
}
