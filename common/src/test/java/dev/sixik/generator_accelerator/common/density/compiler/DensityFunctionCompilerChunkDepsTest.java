package dev.sixik.generator_accelerator.common.density.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DensityFunctionCompilerChunkDepsTest {
    @Test
    void describesUniqueProducerChunksAndBlockedInputs() {
        boolean[] inputs = new boolean[10];
        inputs[0] = true;
        inputs[1] = true;
        inputs[2] = true;
        inputs[4] = true;
        inputs[6] = true;
        inputs[8] = true;
        int[] slotOwners = new int[]{-1, 0, 0, -1, 3, -1, 3};

        assertEquals("2[0,3]",
                DensityFunctionCompiler.describeOpenClChunkProducerSet(inputs, slotOwners, 8));
        assertEquals("2[0,+1]",
                DensityFunctionCompiler.describeOpenClChunkProducerSet(inputs, slotOwners, 1));
        assertEquals("2[0,8]",
                DensityFunctionCompiler.describeOpenClBlockedInputSet(inputs, slotOwners, 8));
        assertEquals("2[0,+1]",
                DensityFunctionCompiler.describeOpenClBlockedInputSet(inputs, slotOwners, 1));
    }

    @Test
    void schedulesChunkWavesAndSeparatesBlockedFromStalledChunks() {
        int[] slotOwners = new int[]{-1, 0, 1, 2, 3};

        DensityFunctionCompiler.OpenClChunkWavePlan plan = DensityFunctionCompiler.collectOpenClChunkWaves(List.of(
                inputs(),
                inputs(1),
                inputs(0),
                inputs(3)
        ), slotOwners);

        assertEquals(2, plan.waves().size());
        assertArrayEquals(new boolean[]{true, false, false, false}, plan.waves().get(0));
        assertArrayEquals(new boolean[]{false, true, false, false}, plan.waves().get(1));
        assertArrayEquals(new boolean[]{true, true, false, false}, plan.scheduledChunks());
        assertArrayEquals(new boolean[]{false, false, true, false}, plan.directBlockedChunks());
        assertArrayEquals(new boolean[]{false, false, false, true}, plan.stalledChunks());
    }

    private static boolean[] inputs(int... slots) {
        boolean[] inputs = new boolean[5];
        for (int slot : slots) {
            inputs[slot] = true;
        }
        return inputs;
    }
}
