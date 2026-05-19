package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DfcOpenClGeneratedNoiseSourceTest {
    @Test
    void stagedChunkSourceUsesSlotMajorGpuBuffer() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(3, 1);
        boolean[] externalSlots = new boolean[]{true, false, false};
        String[] coordX = new String[]{"bx", "slot0 + bx", "bx"};
        String[] coordY = new String[]{"by", "by", "by"};
        String[] coordZ = new String[]{"bz", "bz", "bz"};

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunkSlotBuffer(
                        descriptor, 1, 1, coordX, coordY, coordZ, externalSlots, null,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot0 = external_slots[0 * n + gid];"));
        assertTrue(source.source().contains("out[1 * n + gid] = slot1;"));
    }

    @Test
    void compactStagedChunkSourceUsesCompactSlotIndices() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] externalSlots = new boolean[]{false, true, false, false};
        int[] slotBufferIndices = new int[]{-1, 0, 1, -1};
        String[] coordX = new String[]{"bx", "bx", "slot1 + bx", "bx"};
        String[] coordY = new String[]{"by", "by", "by", "by"};
        String[] coordZ = new String[]{"bz", "bz", "bz", "bz"};

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanChunkCompactSlotBuffer(
                        descriptor, 2, 2, coordX, coordY, coordZ, externalSlots, null,
                        slotBufferIndices, DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot1 = external_slots[0 * n + gid];"));
        assertTrue(source.source().contains("out[1 * n + gid] = slot2;"));
    }
}
