package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Objects;

public final class SurfaceVectorOutput {
    private final BlockState[] states;
    private final boolean[] matched;

    public SurfaceVectorOutput(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        this.states = new BlockState[length];
        this.matched = new boolean[length];
    }

    public int length() {
        return this.states.length;
    }

    public void set(int index, BlockState state) {
        Objects.checkIndex(index, this.states.length);
        this.states[index] = state;
        this.matched[index] = state != null;
    }

    public BlockState state(int index) {
        Objects.checkIndex(index, this.states.length);
        return this.states[index];
    }

    public boolean matched(int index) {
        Objects.checkIndex(index, this.matched.length);
        return this.matched[index];
    }

    public void clear() {
        Arrays.fill(this.states, null);
        Arrays.fill(this.matched, false);
    }
}
