package dev.sixik.generator_accelerator.common.biome.compat.biolith;

import net.minecraft.world.level.biome.Climate;

import java.util.Arrays;

public final class GABiolithSearchContext {
    public Climate.RTree.Node<?>[] stack = new Climate.RTree.Node[128];
    public final long[] parameterArray = new long[7];
    public Climate.RTree.Leaf<?> previousUltimate;
    public Climate.RTree.Leaf<?> previousPenultimate;

    public Climate.RTree.Node<?>[] growStack(int required) {
        int newLength = this.stack.length;
        while (newLength < required) {
            newLength <<= 1;
        }
        this.stack = Arrays.copyOf(this.stack, newLength);
        return this.stack;
    }
}
