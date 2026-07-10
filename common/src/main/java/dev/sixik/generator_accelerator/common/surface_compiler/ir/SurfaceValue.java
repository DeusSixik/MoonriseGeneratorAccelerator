package dev.sixik.generator_accelerator.common.surface_compiler.ir;

import net.minecraft.world.level.block.state.BlockState;

public record SurfaceValue(ValueKind kind, boolean booleanValue, BlockState blockState, String detail) {
    public static SurfaceValue bool(boolean value, String detail) {
        return new SurfaceValue(ValueKind.BOOLEAN, value, null, detail);
    }

    public static SurfaceValue block(BlockState state, String detail) {
        return new SurfaceValue(ValueKind.BLOCK_STATE, false, state, detail);
    }

    public static SurfaceValue none(String detail) {
        return new SurfaceValue(ValueKind.NONE, false, null, detail);
    }

    public enum ValueKind {
        NONE,
        BOOLEAN,
        BLOCK_STATE
    }
}
