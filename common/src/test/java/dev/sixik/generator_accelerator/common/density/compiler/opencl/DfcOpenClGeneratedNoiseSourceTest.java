package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DfcOpenClGeneratedNoiseSourceTest {
    @Test
    void runtimeSourceProvidesBlendedNoisePerlinHelper() {
        assertTrue(DfcOpenClSources.runtimeSource().contains("dfc_perlin_sample_5"));
    }

    @Test
    void runtimeSourceProvidesSlotBufferVmStageKernel() {
        assertTrue(DfcOpenClSources.runtimeSource().contains("dfc_slab_vm_eval_cell_grid_slot_buffer"));
    }

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

    @Test
    void fusedWaveSourceWritesMultipleTargetSlots() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] targetSlots = new boolean[]{false, false, true, true};
        boolean[] externalSlots = new boolean[]{false, true, false, false};
        int[] slotBufferIndices = new int[]{-1, 0, 1, 2};
        String[] coordX = new String[]{"bx", "bx", "slot1 + bx", "bx"};
        String[] coordY = new String[]{"by", "by", "by", "by"};
        String[] coordZ = new String[]{"bz", "bz", "bz", "bz"};

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanWaveCompactSlotBuffer(
                        descriptor, targetSlots, coordX, coordY, coordZ, externalSlots, null,
                        slotBufferIndices, DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot1 = external_slots[0 * n + gid];"));
        assertTrue(source.source().contains("out[1 * n + gid] = slot2;"));
        assertTrue(source.source().contains("out[2 * n + gid] = slot3;"));
    }

    @Test
    void allWavesFusedSourceKeepsScheduledDependenciesLocal() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] targetSlots = new boolean[]{false, true, true, false};
        boolean[] externalSlots = new boolean[]{false, false, false, false};
        int[] slotBufferIndices = new int[]{-1, 0, 1, -1};
        String[] coordX = new String[]{"bx", "bx", "slot1 + bx", "bx"};
        String[] coordY = new String[]{"by", "by", "by", "by"};
        String[] coordZ = new String[]{"bz", "bz", "bz", "bz"};

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                        descriptor, targetSlots, coordX, coordY, coordZ, externalSlots, null,
                        slotBufferIndices, DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot1 = "));
        assertTrue(source.source().contains("double slot2 = "));
        assertTrue(source.source().contains("slot1 + bx"));
        assertTrue(source.source().contains("out[0 * n + gid] = slot1;"));
        assertTrue(source.source().contains("out[1 * n + gid] = slot2;"));
    }

    @Test
    void allWavesFinalOutputSourceReadsExternalInputsRowMajorAndWritesFinalOutput() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] rootSlots = new boolean[]{false, true, true, false};
        boolean[] externalInputs = new boolean[]{false, false, true, false};
        String[] coordX = new String[]{"bx", "bx", "bx", "bx"};
        String[] coordY = new String[]{"by", "by", "by", "by"};
        String[] coordZ = new String[]{"bz", "bz", "bz", "bz"};

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesFinalOutput(
                        descriptor,
                        rootSlots,
                        new byte[]{2, 1, 2, 2, 32},
                        new double[0],
                        "0.0",
                        coordX,
                        coordY,
                        coordZ,
                        externalInputs,
                        null,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot2 = external_slots[gid * 4 + 2];"));
        assertTrue(source.source().contains("stk[sp++] = slot1;"));
        assertTrue(source.source().contains("stk[sp++] = slot2;"));
        assertTrue(source.source().contains("out[gid] = sp == 1 ? stk[0] : 0.0;"));
    }

    @Test
    void finalOutputFromSlotBufferReadsStagedSlotsSlotMajorAndExpandsResidualComputedSlots() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(5, 1);
        boolean[] rootSlots = new boolean[]{false, false, false, false, true};
        boolean[] slotBufferInputs = new boolean[]{false, true, false, true, false};
        int[] slotBufferIndices = new int[]{-1, 0, -1, 1, -1};
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[]{
                null,
                null,
                null,
                null,
                new DfcOpenClRuntime.ComputedSlot(
                        new byte[]{2, 1, 2, 3, 32}, new double[0], null, null, "slot4")
        };

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanFinalOutputFromSlotBuffer(
                        descriptor,
                        rootSlots,
                        new byte[]{2, 4},
                        new double[0],
                        "0.0",
                        null,
                        null,
                        null,
                        slotBufferInputs,
                        computedSlots,
                        slotBufferIndices,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertTrue(source.source().contains("double slot1 = external_slots[0 * n + gid];"));
        assertTrue(source.source().contains("double slot3 = external_slots[1 * n + gid];"));
        assertTrue(source.source().contains("double slot4_stk[DFC_SLAB_STACK];"));
        assertTrue(source.source().contains("stk[sp++] = slot4;"));
    }

    @Test
    void computedSlotSourceSkipsUnusedHoistExpression() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(2, 1);
        boolean[] targetSlots = new boolean[]{false, true};
        boolean[] externalSlots = new boolean[]{true, false};
        int[] slotBufferIndices = new int[]{0, 1};
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[]{
                null,
                new DfcOpenClRuntime.ComputedSlot(
                        new byte[]{2, 0}, new double[0],
                        "slot0 + dfc_unused_huge_hoist()", null, "slot1")
        };

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanAllWavesCompactSlotBuffer(
                        descriptor,
                        targetSlots,
                        null,
                        null,
                        null,
                        externalSlots,
                        computedSlots,
                        slotBufferIndices,
                        DfcOpenClGeneratedNoiseSource.WrapMode.NOWRAP);

        assertFalse(source.source().contains("dfc_unused_huge_hoist"));
    }

    @Test
    void computedSlotBufferSourceReadsOnlyStagedDependencies() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] stagedInputs = new boolean[]{false, true, true, false};
        int[] slotBufferIndices = new int[]{-1, 0, 1, 2};
        DfcOpenClRuntime.ComputedSlot computed = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1, 2, 2, 32}, new double[0], null, null, "slot3");

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotFromSlotBuffer(
                        descriptor, 3, computed, stagedInputs, slotBufferIndices);

        assertTrue(source.source().contains("double slot1 = external_slots[0 * n + gid];"));
        assertTrue(source.source().contains("double slot2 = external_slots[1 * n + gid];"));
        assertTrue(source.source().contains("double slot3_stk[DFC_SLAB_STACK];"));
        assertTrue(source.source().contains("out[2 * n + gid] = slot3;"));
        assertFalse(source.source().contains("double slot0 ="));
    }

    @Test
    void computedSlotBufferSourceUsesCompactUnrolledStackForModeratePrograms() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] stagedInputs = new boolean[]{false, true, false, false};
        int[] slotBufferIndices = new int[]{-1, 0, -1, 1};
        byte[] program = new byte[3002];
        program[0] = 2;
        program[1] = 1;
        for (int i = 2; i < program.length; i++) {
            program[i] = 50;
        }
        DfcOpenClRuntime.ComputedSlot computed = new DfcOpenClRuntime.ComputedSlot(
                program, new double[0], null, null, "slot3");

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotFromSlotBuffer(
                        descriptor, 3, computed, stagedInputs, slotBufferIndices);

        assertTrue(source.source().length() < 196_608);
        assertTrue(source.source().contains("DFC_STK[DFC_SP-1]=DFC_STK[DFC_SP-1] * DFC_STK[DFC_SP-1];"));
        assertFalse(source.source().contains("double slot3_x"));
    }

    @Test
    void computedSlotVmSourceInterpretsBytecodeFromStagedDependencies() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        boolean[] stagedInputs = new boolean[]{false, true, true, false};
        int[] slotBufferIndices = new int[]{-1, 0, 1, 2};
        DfcOpenClRuntime.ComputedSlot computed = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1, 2, 2, 32}, new double[0], null, null, "slot3");

        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildCompiledPlanComputedSlotVmFromSlotBuffer(
                        descriptor, 3, computed, stagedInputs, slotBufferIndices);

        assertTrue(source.source().contains("__constant uchar dfc_generated_bc[] = {2, 1, 2, 2, 32};"));
        assertTrue(source.source().contains("while (pc < bc_len)"));
        assertTrue(source.source().contains("case 1: compact = 0; break;"));
        assertTrue(source.source().contains("case 2: compact = 1; break;"));
        assertTrue(source.source().contains("out[2 * n + gid] = sp == 1 ? stk[0] : 0.0;"));
        assertFalse(source.source().contains("slot3_stk"));
    }

    @Test
    void lazySlabProgramAddsBranchOpcodesForRangeChoice() {
        byte[] program = new byte[]{
                2, 0,
                1, 0, 0,
                1, 1, 0,
                5, 2, 0, 3, 0
        };

        byte[] lazy = DfcOpenClRuntime.lazySlabProgram(program);

        assertTrue(lazy.length > program.length);
        assertTrue(DfcOpenClRuntime.slabProgramUsesLazyBranch(lazy));
    }

    @Test
    void lazySlabProgramKeepsStraightLineProgramUnchanged() {
        byte[] program = new byte[]{2, 0, 2, 1, 32};

        byte[] lazy = DfcOpenClRuntime.lazySlabProgram(program);

        assertArrayEquals(program, lazy);
    }

    @Test
    void lazySlabProgramMatchesEagerRangeChoiceEvaluation() {
        byte[] program = new byte[]{
                2, 0,
                1, 0, 0,
                1, 1, 0,
                5, 2, 0, 3, 0
        };
        byte[] lazy = DfcOpenClRuntime.lazySlabProgram(program);
        double[] constants = new double[]{42.0D, -7.0D, 0.0D, 10.0D};

        assertEquals(42.0D, DfcOpenClRuntime.evalCompiledPlanProgram(
                lazy, constants, new double[]{5.0D}, 0.0D, 0.0D, 0.0D, 0.0D));
        assertEquals(-7.0D, DfcOpenClRuntime.evalCompiledPlanProgram(
                lazy, constants, new double[]{11.0D}, 0.0D, 0.0D, 0.0D, 0.0D));
    }

    @Test
    void finalOutputVmStageReportsUploadBytesAndStartsUnuploaded() {
        DfcOpenClDeviceContext.FinalOutputStage stage =
                DfcOpenClDeviceContext.FinalOutputStage.slabVmSlot(
                        new byte[]{1, 0, 0}, new double[]{2.0D, 3.0D}, 4);

        assertFalse(stage.vmBuffersUploaded());
        assertEquals(19L, stage.vmUploadBytes());
    }

    @Test
    void finalOutputExternalInputsOnlyIncludeReachableMarkerExterns() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "reachable",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
                new byte[]{2, 0, 2, 4, 32},
                new double[0],
                null,
                null,
                new String[]{null, null, null, null, "slot6 + bx", null, null},
                null,
                null,
                null,
                null,
                null,
                null,
                new boolean[]{false, false, false, true, false},
                null,
                null,
                new DfcOpenClRuntime.ComputedSlot[]{
                        null,
                        null,
                        null,
                        null,
                        new DfcOpenClRuntime.ComputedSlot(
                                new byte[]{2, 1, 2, 3, 32}, new double[0], null, null, "slot4")
                });

        boolean[] inputs = DfcOpenClRuntime.compiledPlanFinalOutputExternalInputs(plan, 5);

        assertFalse(inputs[0]);
        assertFalse(inputs[1]);
        assertFalse(inputs[2]);
        assertTrue(inputs[3]);
        assertFalse(inputs[4]);
    }

    @Test
    void finalOutputComputedSlotsIncludeReachableComputedRoots() {
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[]{
                null,
                null,
                null,
                null,
                new DfcOpenClRuntime.ComputedSlot(
                        new byte[]{2, 1, 2, 3, 32}, new double[0], null, null, "slot4")
        };
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "reachable",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
                new byte[]{2, 4},
                new double[0],
                null,
                null,
                new String[]{null, null, null, null, "slot6 + bx", null, null},
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                computedSlots);

        DfcOpenClRuntime.ComputedSlot[] finalOutputComputed =
                DfcOpenClRuntime.compiledPlanFinalOutputComputedSlots(plan, 5);

        assertTrue(finalOutputComputed[4] == computedSlots[4]);
    }

    @Test
    void finalOutputResidualGpuInputsStageUnscheduledNonExternalOutputRoots() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "stage-roots",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[6],
                new byte[]{19, 2, 4, 32},
                new double[0],
                "slot5",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new boolean[]{false, false, true, false, false, false},
                new int[]{-1, -1, 0, -1, -1, -1},
                null,
                new DfcOpenClRuntime.ComputedSlot[]{
                        null,
                        null,
                        null,
                        null,
                        new DfcOpenClRuntime.ComputedSlot(
                                new byte[]{2, 1, 2, 2, 32}, new double[0], null, null, "slot4"),
                        null
                });
        boolean[] scheduledSlots = new boolean[]{false, true, false, false, false, false};

        boolean[] gpuInputs = DfcOpenClRuntime.compiledPlanFinalOutputResidualGpuInputSlots(
                plan, scheduledSlots, 6);

        assertFalse(gpuInputs[1]);
        assertFalse(gpuInputs[2]);
        assertTrue(gpuInputs[4]);
        assertTrue(gpuInputs[5]);
    }

    @Test
    void finalOutputCpuInputsKeepMarkerDependenciesButSkipGpuResidualRoots() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "stage-roots",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[6],
                new byte[]{2, 4},
                new double[0],
                "slot5",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new boolean[]{false, false, true, false, false, false},
                new int[]{-1, -1, 0, -1, -1, -1},
                null,
                new DfcOpenClRuntime.ComputedSlot[]{
                        null,
                        null,
                        null,
                        null,
                        new DfcOpenClRuntime.ComputedSlot(
                                new byte[]{2, 1, 2, 2, 32}, new double[0], null, null, "slot4"),
                        null
                });
        boolean[] scheduledSlots = new boolean[]{false, true, false, false, false, false};
        boolean[] gpuResidualSlots = new boolean[]{false, false, false, false, true, true};
        boolean[] cpuResidualDependencySlots = new boolean[]{false, false, false, true, false, false};

        boolean[] cpuInputs = DfcOpenClRuntime.compiledPlanFinalOutputCpuInputSlots(
                plan, scheduledSlots, gpuResidualSlots, cpuResidualDependencySlots, 6);

        assertFalse(cpuInputs[1]);
        assertTrue(cpuInputs[2]);
        assertTrue(cpuInputs[3]);
        assertFalse(cpuInputs[4]);
        assertFalse(cpuInputs[5]);
    }

    @Test
    void residualDependencyCpuFallbackChoosesOneSmallestUnresolvedSlot() {
        boolean[] candidates = new boolean[]{false, true, true, true};
        boolean[] gpuSlots = new boolean[]{false, false, false, true};
        boolean[] cpuSlots = new boolean[]{false, true, false, false};
        long[] rejectedSourceChars = new long[]{0L, 10L, 7L, 3L};

        int fallbackSlot = DfcOpenClRuntime.residualDependencyCpuFallbackSlot(
                candidates, gpuSlots, cpuSlots, rejectedSourceChars);

        assertTrue(fallbackSlot == 2);
    }

    @Test
    void computedSlotDependenciesStagedRequiresProgramAndHoistInputs() {
        DfcOpenClRuntime.ComputedSlot computed = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1, 19, 32}, new double[0], "slot2 + bx", null, "slot3");

        assertFalse(DfcOpenClRuntime.computedSlotDependenciesStaged(
                computed, new boolean[]{false, true, false, false}, 3, 4));
        assertTrue(DfcOpenClRuntime.computedSlotDependenciesStaged(
                computed, new boolean[]{false, true, true, false}, 3, 4));
    }

    @Test
    void computedSlotUnrolledSourceEstimateRejectsHugePrograms() {
        byte[] hugeProgram = new byte[6000];
        hugeProgram[0] = 2;
        hugeProgram[1] = 1;
        for (int i = 2; i < hugeProgram.length; i++) {
            hugeProgram[i] = 50;
        }
        DfcOpenClRuntime.ComputedSlot computed = new DfcOpenClRuntime.ComputedSlot(
                hugeProgram, new double[0], null, null, "huge");

        assertFalse(DfcOpenClRuntime.computedSlotUnrolledSourceLikelyFits(computed));
    }

    @Test
    void computedSlotDeviceVmStageHandlesHugeNoHoistProgramsOnly() {
        byte[] moderateProgram = new byte[3002];
        moderateProgram[0] = 2;
        moderateProgram[1] = 1;
        for (int i = 2; i < moderateProgram.length; i++) {
            moderateProgram[i] = 50;
        }
        DfcOpenClRuntime.ComputedSlot moderateNoHoist = new DfcOpenClRuntime.ComputedSlot(
                moderateProgram, new double[0], "0.0", null, "moderate/no-hoist");
        byte[] hugeProgram = new byte[30_000];
        hugeProgram[0] = 2;
        hugeProgram[1] = 1;
        for (int i = 2; i < hugeProgram.length; i++) {
            hugeProgram[i] = 50;
        }
        DfcOpenClRuntime.ComputedSlot noHoist = new DfcOpenClRuntime.ComputedSlot(
                hugeProgram, new double[0], "0.0", null, "huge/no-hoist");
        DfcOpenClRuntime.ComputedSlot withHoist = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{19}, new double[0], "bx", null, "huge/hoist");

        assertFalse(DfcOpenClRuntime.computedSlotUsesDeviceVmStage(moderateNoHoist));
        assertTrue(DfcOpenClRuntime.computedSlotUsesDeviceVmStage(noHoist));
        assertFalse(DfcOpenClRuntime.computedSlotUsesDeviceVmStage(withHoist));
    }

    @Test
    void finalOutputResidualDependencySlotsStageComputedRootInputsBeforeRoot() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "stage-dependencies",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[7],
                new byte[]{2, 0},
                new double[0],
                null,
                null,
                new String[]{null, null, null, null, "slot6 + bx", null, null},
                null,
                null,
                null,
                null,
                null,
                null,
                new boolean[]{false, false, true, false, false, false, false},
                new int[]{-1, -1, 0, -1, -1, -1, -1},
                null,
                new DfcOpenClRuntime.ComputedSlot[]{
                        new DfcOpenClRuntime.ComputedSlot(
                                new byte[]{2, 1, 2, 2, 2, 4, 32, 19, 32, 32}, new double[0],
                                "slot5", null, "root0"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                });
        boolean[] residualCandidates = new boolean[]{true, false, false, false, false, false, false};
        boolean[] scheduledSlots = new boolean[]{false, true, false, false, false, false, false};
        boolean[] markerExternalInputs = new boolean[]{false, false, true, false, false, false, false};

        boolean[] dependencySlots = DfcOpenClRuntime.compiledPlanFinalOutputResidualDependencySlots(
                plan, residualCandidates, scheduledSlots, markerExternalInputs, 7);

        assertFalse(dependencySlots[0]);
        assertFalse(dependencySlots[1]);
        assertFalse(dependencySlots[2]);
        assertTrue(dependencySlots[4]);
        assertTrue(dependencySlots[5]);
        assertTrue(dependencySlots[6]);
    }

    @Test
    void finalOutputSlotDescriptionShowsSlotKindsAndLimit() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "describe",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[4],
                new byte[]{2, 0},
                new double[0],
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new boolean[]{true, false, false, false},
                new int[]{0, -1, -1, -1},
                new net.minecraft.world.level.levelgen.DensityFunction[]{null},
                new DfcOpenClRuntime.ComputedSlot[]{
                        null,
                        new DfcOpenClRuntime.ComputedSlot(
                                new byte[]{2, 0}, new double[0], null, null, "computed/one"),
                        null,
                        null
                });

        String description = DfcOpenClRuntime.describeFinalOutputInputSlots(
                plan, new boolean[]{true, true, true, false}, 2);

        assertTrue(description.startsWith("3[0:external#0:null"));
        assertTrue(description.contains("; 1:computed:computed/one"));
        assertTrue(description.endsWith("; +1]"));
    }

    @Test
    void waveSlotBufferValidationChecksCompactSlotMajorValues() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(3, 1);
        double[] requestOut = new double[0];
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                DfcOpenClSlabVmSmoke.noiseCellGridRequest(requestOut, 2, 2, 2, descriptor);
        int[] slotBufferIndices = new int[]{-1, 0, 1};
        boolean[] targetSlots = new boolean[]{false, true, true};
        double[] slotBuffer = new double[request.n() * 2];
        for (int element = 0; element < request.n(); element++) {
            double bx = testCellBlockX(element, request);
            double by = testCellBlockY(element, request);
            double bz = testCellBlockZ(element, request);
            slotBuffer[element] = descriptor.sampleSlot(1, bx, by, bz);
            slotBuffer[request.n() + element] = descriptor.sampleSlot(2, bx, by, bz);
        }

        assertDoesNotThrow(() -> DfcOpenClRuntime.validateCompiledPlanWaveSlotBuffer(
                slotBuffer, request, descriptor, slotBufferIndices, targetSlots,
                null, null, null, null, null, null, descriptor.slotCount, 17));

        slotBuffer[request.n()] += 1.0D;
        assertThrows(IllegalStateException.class, () -> DfcOpenClRuntime.validateCompiledPlanWaveSlotBuffer(
                slotBuffer, request, descriptor, slotBufferIndices, targetSlots,
                null, null, null, null, null, null, descriptor.slotCount, 17));
    }

    @Test
    void hybridFinalDensityValidationUsesGpuSlotBufferForScheduledSlots() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(3, 1);
        double[] requestOut = new double[0];
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                DfcOpenClSlabVmSmoke.noiseCellGridRequest(requestOut, 2, 2, 2, descriptor);
        int[] slotBufferIndices = new int[]{-1, 0, -1};
        boolean[] targetSlots = new boolean[]{false, true, false};
        double[] slotBuffer = new double[request.n()];
        for (int element = 0; element < request.n(); element++) {
            double bx = testCellBlockX(element, request);
            double by = testCellBlockY(element, request);
            double bz = testCellBlockZ(element, request);
            slotBuffer[element] = descriptor.sampleSlot(1, bx, by, bz);
        }
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "synthetic",
                null,
                new byte[]{2, 1, 2, 2, 32},
                new double[0],
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertDoesNotThrow(() -> DfcOpenClRuntime.validateCompiledPlanHybridFinalDensity(
                slotBuffer, request, descriptor, plan, slotBufferIndices, targetSlots, descriptor.slotCount, 17));

        slotBuffer[0] += 1.0D;
        assertThrows(IllegalStateException.class, () -> DfcOpenClRuntime.validateCompiledPlanHybridFinalDensity(
                slotBuffer, request, descriptor, plan, slotBufferIndices, targetSlots, descriptor.slotCount, 17));
    }

    @Test
    void hybridFinalDensityValidationTreatsMatchingNaNsAsEqual() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(3, 1);
        double[] requestOut = new double[0];
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                DfcOpenClSlabVmSmoke.noiseCellGridRequest(requestOut, 2, 2, 2, descriptor);
        int[] slotBufferIndices = new int[]{-1, 0, -1};
        boolean[] targetSlots = new boolean[]{false, true, false};
        double[] slotBuffer = new double[request.n()];
        for (int element = 0; element < request.n(); element++) {
            double bx = testCellBlockX(element, request);
            double by = testCellBlockY(element, request);
            double bz = testCellBlockZ(element, request);
            slotBuffer[element] = descriptor.sampleSlot(1, bx, by, bz);
        }
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "nan",
                null,
                new byte[]{2, 1, 2, 1, 33, 2, 1, 2, 1, 33, 35},
                new double[0],
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertDoesNotThrow(() -> DfcOpenClRuntime.validateCompiledPlanHybridFinalDensity(
                slotBuffer, request, descriptor, plan, slotBufferIndices, targetSlots, descriptor.slotCount, 17));
    }

    @Test
    void runtimeCellFillIndexMapsJavaFillOrderToOpenClElementOrder() {
        int cellWidth = 4;
        int cellHeight = 8;
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(0, cellWidth, cellHeight) == 0);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(1, cellWidth, cellHeight) == 16);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(7, cellWidth, cellHeight) == 112);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(8, cellWidth, cellHeight) == 1);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(31, cellWidth, cellHeight) == 115);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(32, cellWidth, cellHeight) == 4);
        assertTrue(DfcOpenClRuntime.runtimeCellFillElementIndex(127, cellWidth, cellHeight) == 127);
    }

    @Test
    void runtimeHybridAcceptsReboundFinalDensitySizedPlan() {
        assertFalse(DfcOpenClRuntime.runtimeHybridCandidateSlotCount(2));
        assertFalse(DfcOpenClRuntime.runtimeHybridCandidateSlotCount(5));
        assertTrue(DfcOpenClRuntime.runtimeHybridCandidateSlotCount(54));
    }

    @Test
    void runtimeHybridSkipsPerCellDispatchBelowMinimumSlotValues() {
        String oldMin = System.getProperty("dfc.opencl.finalDensityHybridMinSlotValues");
        try {
            System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            assertFalse(DfcOpenClRuntime.runtimeHybridCellValuesCanReachMinimum(128));
            assertFalse(DfcOpenClRuntime.runtimeHybridSlotValuesMeetMinimum(54 * 128));
            assertTrue(DfcOpenClRuntime.runtimeHybridSlotValuesMeetMinimum(16_384));

            System.setProperty("dfc.opencl.finalDensityHybridMinSlotValues", "4096");
            assertTrue(DfcOpenClRuntime.runtimeHybridCellValuesCanReachMinimum(128));
            assertTrue(DfcOpenClRuntime.runtimeHybridSlotValuesMeetMinimum(54 * 128));
        } finally {
            if (oldMin == null) {
                System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            } else {
                System.setProperty("dfc.opencl.finalDensityHybridMinSlotValues", oldMin);
            }
        }
    }

    @Test
    void runtimeHybridFastSkipPathIsNotGloballySynchronized() throws NoSuchMethodException {
        int modifiers = DfcOpenClRuntime.class.getDeclaredMethod(
                "tryFillFinalDensityHybrid", CompiledDensityFunction.class, double[].class, NoiseChunk.class)
                .getModifiers();
        assertFalse(Modifier.isSynchronized(modifiers));
    }

    @Test
    void runtimeHybridDoesNotCachePlansThatRetainRuntimeExterns() {
        assertFalse(DfcOpenClRuntime.runtimeHybridPlanCacheable(true));
        assertTrue(DfcOpenClRuntime.runtimeHybridPlanCacheable(false));
    }

    @Test
    void slabProgramSlotDependenciesContainOnlyReferencedRootSlots() {
        byte[] program = new byte[]{
                2, 3,
                2, 1,
                32,
                2, 3,
                34
        };

        assertArrayEquals(new int[]{1, 3}, DfcOpenClRuntime.slotDependencies(program, 5));
    }

    private static double testCellBlockX(int element,
                                         DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int cellVolume = request.cellWidth() * request.cellWidth() * request.cellHeight();
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (request.cellWidth() * request.cellWidth());
        int ix = plane / request.cellWidth();
        int cellX = cell & 31;
        return request.firstBlockX() + cellX * request.cellWidth() + ix;
    }

    private static double testCellBlockY(int element,
                                         DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int planeSize = request.cellWidth() * request.cellWidth();
        int inCell = element % (planeSize * request.cellHeight());
        int yIndex = inCell / planeSize;
        return request.firstBlockY() + (request.cellHeight() - 1 - yIndex);
    }

    private static double testCellBlockZ(int element,
                                         DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request) {
        int cellVolume = request.cellWidth() * request.cellWidth() * request.cellHeight();
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (request.cellWidth() * request.cellWidth());
        int iz = plane % request.cellWidth();
        int cellZ = cell >> 5;
        return request.firstBlockZ() + cellZ * request.cellWidth() + iz;
    }
}
