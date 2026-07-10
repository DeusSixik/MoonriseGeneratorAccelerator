package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceReadView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class LegacySurfaceReadView {
    private final SurfaceReadView delegate;
    private final BorrowToken token;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LegacySurfaceReadView(SurfaceReadView delegate, BorrowToken token) {
        this.delegate = delegate;
        this.token = token;
    }

    public BlockState getBlockState(int x, int y, int z) {
        this.token.checkOpen();
        this.pos.set(x, y, z);
        return this.delegate.getBlockState(x, y, z);
    }

    public FluidState getFluidState(int x, int y, int z) {
        this.token.checkOpen();
        this.pos.set(x, y, z);
        return this.delegate.getFluidState(x, y, z);
    }
}
