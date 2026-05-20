package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCellCacheAccess;
import dev.sixik.generator_accelerator.common.density.compiler.cache.DfcCacheFastPath;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.CompiledDensityFunction;
import dev.sixik.generator_accelerator.common.noise.NoiseChunk$FlatCache$FlatArray;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DfcOpenClGeneratedNoiseSourceTest {
    @Test
    void runtimeSourceProvidesBlendedNoisePerlinHelper() {
        assertTrue(DfcOpenClSources.runtimeSource().contains("dfc_perlin_sample_5"));
    }

    @Test
    void perlinHelperMicrobenchSourceCallsBaseHelperOncePerSample() {
        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildPerlinHelperMicrobench(3, false);

        assertTrue(source.source().contains("__kernel void " + DfcOpenClGeneratedNoiseSource.KERNEL_NAME));
        assertEquals(3, source.source().split("dfc_perlin_sample\\(permutations \\+", -1).length - 1);
        assertFalse(source.source().contains("dfc_perlin_sample_5("));
    }

    @Test
    void perlinHelperMicrobenchSourceCallsSample5HelperOncePerSample() {
        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildPerlinHelperMicrobench(4, true);

        assertTrue(source.source().contains("__kernel void " + DfcOpenClGeneratedNoiseSource.KERNEL_NAME));
        assertEquals(4, source.source().split("dfc_perlin_sample_5\\(permutations \\+", -1).length - 1);
        assertTrue(source.source().contains(", 0.125, by)"));
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
    void flatCache2dPrefillSourceUsesFloorQuartCoordinatesAndSlotMajorOutput() {
        DfcOpenClGeneratedNoiseSource.BuildResult source =
                DfcOpenClGeneratedNoiseSource.buildFlatCache2dSlotBufferPrefill();

        assertTrue(source.source().contains("dfc_floor_div4"));
        assertTrue(source.source().contains("int quart_x = dfc_floor_div4(block_x);"));
        assertTrue(source.source().contains("int flat_index = local_x * side + local_z;"));
        assertTrue(source.source().contains("slot_buffer[compact_index * n + gid]"));
    }

    @Test
    void generatedSourcesPassCellGridLayout() {
        String generated = DfcOpenClGeneratedNoiseSource.build(DfcOpenClNoiseDescriptor.synthetic(1, 1), 1).source();
        String flat = DfcOpenClGeneratedNoiseSource.buildFlatCache2dSlotBufferPrefill().source();

        assertTrue(generated.contains("int layout"));
        assertTrue(generated.contains("cell_w, cell_h, cells, layout, &bx, &by, &bz, &cell"));
        assertTrue(flat.contains("int layout"));
        assertTrue(flat.contains("cellWidth, cellHeight, cells, layout"));
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
    void finalOutputStageTraceListsTopGeneratedDependencyStages() {
        DfcOpenClRuntime.FinalOutputTraceStageInfo[] infos = new DfcOpenClRuntime.FinalOutputTraceStageInfo[]{
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("wave", "wave:slotBuffer/src=10", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:5:foo/gen/src=100", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:6:bar/gen/src=100", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:7:baz/gen/src=100", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("root", "root:0:out/gen/src=100", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:19:vm/vm/bc=4/consts=2/buf=1", true)
        };
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputStageTraceTimes(
                infos,
                new long[]{10L * millis, 5L * millis, 30L * millis, 20L * millis, 7L * millis, 11L * millis},
                4L * millis,
                0L,
                2);

        assertTrue(trace.contains("generatedDepTop=3[dep:6:bar/gen/src=100=15.000; "
                + "dep:7:baz/gen/src=100=10.000; dep:5:foo/gen/src=100=2.500]"));
        assertTrue(trace.contains("generatedRootTop=1[root:0:out/gen/src=100=3.500]"));
        assertTrue(trace.contains("vmStages=1[dep:19:vm/vm/bc=4/consts=2/buf=1=5.500]"));
    }

    @Test
    void finalOutputStageTraceReportsInputWritesAndWaveSubmitWaitSplit() {
        DfcOpenClRuntime.FinalOutputTraceStageInfo[] infos = new DfcOpenClRuntime.FinalOutputTraceStageInfo[]{
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("wave", "wave:slotBuffer/src=10", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:5:foo/gen/src=100", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("root", "root:0:out/gen/src=100", false)
        };
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputStageTraceTimes(
                infos,
                new long[]{25L * millis, 5L * millis, 7L * millis},
                new long[]{3L * millis, 1L * millis, 2L * millis},
                new long[]{22L * millis, 4L * millis, 5L * millis},
                3L * millis,
                2L * millis,
                4L * millis,
                2L * millis,
                5L * millis,
                9L * millis,
                5);

        assertTrue(trace.contains("inputWriteMs=0.600"));
        assertTrue(trace.contains("initialSlotWriteMs=0.400"));
        assertTrue(trace.contains("waveSubmitMs=0.600"));
        assertTrue(trace.contains("waveWaitMs=4.400"));
        assertTrue(trace.contains("finalSubmitMs=0.400"));
        assertTrue(trace.contains("finalWaitMs=1.000"));
    }

    @Test
    void finalOutputStageTraceListsTopWaveStages() {
        DfcOpenClRuntime.FinalOutputTraceStageInfo[] infos = new DfcOpenClRuntime.FinalOutputTraceStageInfo[]{
                new DfcOpenClRuntime.FinalOutputTraceStageInfo(
                        "wave", "wave:0+12:computed:foo/gen/src=20000", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo(
                        "wave", "wave:32+8:computed:bar/gen/src=12000", false),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:5:foo/gen/src=100", false)
        };
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputStageTraceTimes(
                infos,
                new long[]{10L * millis, 30L * millis, 5L * millis},
                0L,
                0L,
                2);

        assertTrue(trace.contains("waveTop=2[wave:32+8:computed:bar/gen/src=12000=15.000; "
                + "wave:0+12:computed:foo/gen/src=20000=5.000]"));
    }

    @Test
    void finalOutputStageTraceListsWaveSlotDetailsForSlowestWaveStages() {
        DfcOpenClRuntime.FinalOutputTraceStageInfo[] infos = new DfcOpenClRuntime.FinalOutputTraceStageInfo[]{
                new DfcOpenClRuntime.FinalOutputTraceStageInfo(
                        "wave", "wave:0+12:computed:foo/gen/src=20000", false,
                        "slotTop=1[0:computed:foo/src=9000]"),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo(
                        "wave", "wave:32+8:computed:bar/gen/src=12000", false,
                        "slotTop=1[32:computed:bar/src=7000]"),
                new DfcOpenClRuntime.FinalOutputTraceStageInfo("dep", "dep:5:foo/gen/src=100", false)
        };
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputStageTraceTimes(
                infos,
                new long[]{10L * millis, 30L * millis, 5L * millis},
                0L,
                0L,
                2);

        assertTrue(trace.contains("waveSlotTop=2[wave:32+8:computed:bar/gen/src=12000/"
                + "slotTop=1[32:computed:bar/src=7000]; wave:0+12:computed:foo/gen/src=20000/"
                + "slotTop=1[0:computed:foo/src=9000]]"));
    }

    @Test
    void finalOutputStageTraceListsWaveAggregateSampleCost() {
        DfcOpenClRuntime.FinalOutputTraceStageInfo[] infos = new DfcOpenClRuntime.FinalOutputTraceStageInfo[]{
                new DfcOpenClRuntime.FinalOutputTraceStageInfo(
                        "wave", "wave:0+4:noise/gen/src=100", false, null,
                        new DfcOpenClGeneratedNoiseSource.SourceMetrics(4, 6, "feedbeef"))
        };

        String trace = DfcOpenClRuntime.describeFinalOutputStageTraceTimes(
                infos,
                new long[]{8_000L},
                new long[]{0L},
                new long[]{8_000L},
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                2,
                100);

        assertTrue(trace.contains("waveAgg=1[wave:0+4:noise/gen/src=100/oct=4/ops=6/sampleNs=10.0]"));
    }

    @Test
    void finalOutputWaveSlotTraceReportsOctavesAndSlabOpsPerClosure() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(3, 1);
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[3];
        computedSlots[2] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 0, 2, 1, 32, 50}, new double[0], null, null, "slot2");
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "slot-trace",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[3],
                new byte[]{2, 2},
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
                computedSlots);

        String trace = DfcOpenClRuntime.finalOutputWaveStageSlotTop(
                descriptor,
                plan,
                new boolean[]{false, false, true},
                new boolean[]{false, false, false},
                computedSlots,
                new int[]{0, 1, 2},
                4);

        assertTrue(trace.contains("2:computed:slot2/src="));
        assertTrue(trace.contains("/oct=4/ops=4"));
    }

    @Test
    void finalOutputWaveSlotTraceReportsDuplicateClosureFingerprints() {
        DfcOpenClNoiseDescriptor descriptor = DfcOpenClNoiseDescriptor.synthetic(4, 1);
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[4];
        computedSlots[2] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 0, 2, 1, 32}, new double[0], null, null, "slot2");
        computedSlots[3] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 0, 2, 1, 32}, new double[0], null, null, "slot3");
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "slot-fingerprint",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[4],
                new byte[]{2, 2, 2, 3, 32},
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
                computedSlots);

        String trace = DfcOpenClRuntime.finalOutputWaveStageSlotTop(
                descriptor,
                plan,
                new boolean[]{false, false, true, true},
                new boolean[]{true, true, false, false},
                computedSlots,
                new int[]{0, 1, 2, 3},
                4);

        assertTrue(trace.contains("/fp="));
        assertTrue(trace.contains("/dup=2"));
    }

    @Test
    void externalPrefillTraceListsTopInputSlotsAndUnattributedTime() {
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[5];
        computedSlots[2] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1}, new double[0], null, null, "slot2");
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "prefill-trace",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                null,
                null,
                null,
                computedSlots);
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputExternalPrefillTrace(
                plan,
                new boolean[]{false, true, true, false, true},
                new DfcOpenClRuntime.FinalOutputExternalPrefillTrace(
                        100L * millis,
                        3L * millis,
                        4L * millis,
                        5L * millis,
                        new long[]{0L, 9L * millis, 15L * millis, 0L, 6L * millis},
                        new int[]{0, 4, 4, 0, 2}),
                2);

        assertTrue(trace.contains("totalMs=100.000"));
        assertTrue(trace.contains("otherMs=58.000"));
        assertTrue(trace.contains("slotTop=3[2:computed:slot2=15.000/4; 1:noise=9.000/4; +1]"));
    }

    @Test
    void externalPrefillTraceBreaksDownRoutingAllocationAndCopyWork() {
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "prefill-breakdown",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[3],
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
                null,
                null,
                null,
                null);
        long millis = 1_000_000L;

        String trace = DfcOpenClRuntime.describeFinalOutputExternalPrefillTrace(
                plan,
                new boolean[]{true, false, true},
                new DfcOpenClRuntime.FinalOutputExternalPrefillTrace(
                        100L * millis,
                        7L * millis,
                        3L * millis,
                        2L * millis,
                        11L * millis,
                        5L * millis,
                        6L * millis,
                        13L * millis,
                        17L * millis,
                        19L * millis,
                        new long[]{23L * millis, 0L, 29L * millis},
                        new int[]{8, 0, 8}),
                4);

        assertTrue(trace.contains("classifyMs=7.000"));
        assertTrue(trace.contains("traceAllocMs=2.000"));
        assertTrue(trace.contains("coordMs=5.000"));
        assertTrue(trace.contains("localAllocMs=6.000"));
        assertTrue(trace.contains("indexMs=17.000"));
        assertTrue(trace.contains("copyMs=19.000"));
        assertTrue(trace.contains("otherMs=12.000"));
    }

    @Test
    void directExternalInputSlotsOnlyAcceptsMarkerExternalInputs() {
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[5];
        computedSlots[2] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1}, new double[0], null, null, "slot2");
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "direct-externals",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, true, false},
                null,
                null,
                computedSlots);

        assertArrayEquals(new int[]{1, 3}, DfcOpenClRuntime.finalOutputDirectExternalInputSlots(
                plan, new boolean[]{false, true, false, true, false}, 5));
        assertNull(DfcOpenClRuntime.finalOutputDirectExternalInputSlots(
                plan, new boolean[]{false, true, true, false, false}, 5));
        assertNull(DfcOpenClRuntime.finalOutputDirectExternalInputSlots(
                plan, new boolean[]{false, true, false, false, true}, 5));
    }

    @Test
    void directExternalSlotBufferCopyGathersRowMajorInputsIntoSlotMajorCompactBuffer() {
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        5, 0, 0,
                        0, 0, 0,
                        1, 1, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 3);
        double[] rowMajorExternalValues = new double[]{
                0.0D, 1.0D, 2.0D, 3.0D, 4.0D,
                10.0D, 11.0D, 12.0D, 13.0D, 14.0D,
                20.0D, 21.0D, 22.0D, 23.0D, 24.0D
        };
        double[] compactSlotMajorValues = new double[6];

        DfcOpenClRuntime.copyDirectExternalSlotBufferInputs(
                request, rowMajorExternalValues, compactSlotMajorValues,
                new int[]{1, 4}, new int[]{0, 1});

        assertArrayEquals(
                new double[]{1.0D, 11.0D, 21.0D, 4.0D, 14.0D, 24.0D},
                compactSlotMajorValues);
    }

    @Test
    void directExternalSlotBufferPrefillComputesMarkerExternsIntoSlotMajorCompactBuffer() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "direct-external-prefill",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, false, true},
                new int[]{-1, 0, -1, -1, 1},
                new DensityFunction[]{
                        new TestExternalDensityFunction(1_000.0D),
                        new TestExternalDensityFunction(2_000.0D)
                },
                null);
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        5, 0, 0,
                        2, 10, 30,
                        1, 3, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 3);
        double[] compactSlotMajorValues = new double[6];

        DfcOpenClRuntime.fillDirectExternalSlotBufferInputs(
                plan, request, compactSlotMajorValues, new int[]{1, 4}, new int[]{0, 1});

        assertArrayEquals(new double[]{
                4_122.0D, 4_112.0D, 4_102.0D,
                5_122.0D, 5_112.0D, 5_102.0D
        }, compactSlotMajorValues);
    }

    @Test
    void externalSlotPrefillUsesConstantRangeWithoutCallingCompute() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "constant-external-prefill",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, false, true},
                new int[]{-1, 0, -1, -1, 1},
                new DensityFunction[]{
                        new ConstantRangeDensityFunction(3.0D),
                        new ConstantRangeDensityFunction(-2.0D)
                },
                null);
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        5, 0, 0,
                        0, 0, 0,
                        1, 1, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 3);
        double[] compactSlotMajorValues = new double[6];

        double[] rowMajorValues = DfcOpenClRuntime.fillExternalSlots(plan, request, 5);
        DfcOpenClRuntime.fillDirectExternalSlotBufferInputs(
                plan, request, compactSlotMajorValues, new int[]{1, 4}, new int[]{0, 1});

        assertArrayEquals(new double[]{
                0.0D, 3.0D, 0.0D, 0.0D, -2.0D,
                0.0D, 3.0D, 0.0D, 0.0D, -2.0D,
                0.0D, 3.0D, 0.0D, 0.0D, -2.0D
        }, rowMajorValues);
        assertArrayEquals(new double[]{3.0D, 3.0D, 3.0D, -2.0D, -2.0D, -2.0D},
                compactSlotMajorValues);
    }

    @Test
    void externalSlotPrefillDoesNotTreatCacheWrapperRangeAsConstant() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "flat-cache-like-external-prefill",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[3],
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
                new boolean[]{false, true, false},
                new int[]{-1, 0, -1},
                new DensityFunction[]{
                        new FlatCacheLikeDensityFunction(1_000.0D)
                },
                null);
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        3, 0, 0,
                        2, 10, 30,
                        1, 3, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 3);
        double[] compactSlotMajorValues = new double[3];

        double[] rowMajorValues = DfcOpenClRuntime.fillExternalSlots(plan, request, 3);
        DfcOpenClRuntime.fillDirectExternalSlotBufferInputs(
                plan, request, compactSlotMajorValues, new int[]{1}, new int[]{0});

        assertArrayEquals(new double[]{
                0.0D, 4_122.0D, 0.0D,
                0.0D, 4_112.0D, 0.0D,
                0.0D, 4_102.0D, 0.0D
        }, rowMajorValues);
        assertArrayEquals(new double[]{4_122.0D, 4_112.0D, 4_102.0D}, compactSlotMajorValues);
        assertFalse(DfcOpenClRuntime.directExternalSlotBufferInputsConstant(plan, new int[]{1}));
    }

    @Test
    void flatCacheLikeTestFixtureExposes2dMetadata() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        FlatCache2dDensityFunction function = new FlatCache2dDensityFunction(
                new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, -3, 7);

        assertArrayEquals(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, function.bts$getArray());
        assertEquals(2, function.bts$getSide());
        assertEquals(-3, function.bts$getFirstNoiseX());
        assertEquals(7, function.bts$getFirstNoiseZ());
    }

    @Test
    void directExternalSlotClassificationTreatsUniformFlatCacheAsConstant() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{7.0D, 7.0D, 7.0D, 7.0D}, 2, 0, 0));

        DfcOpenClRuntime.ExternalInputClassification classification =
                DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

        assertFalse(classification.requiresCpuFallback());
        assertEquals(DfcOpenClRuntime.ExternalInputKind.CONSTANT, classification.slots()[0].kind());
        assertEquals(7.0D, classification.slots()[0].constantValue());
    }

    @Test
    void directExternalSlotClassificationTreatsNonUniformFlatCacheAsFlatCache2d() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, -1, 5));

        DfcOpenClRuntime.ExternalInputClassification classification =
                DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

        assertFalse(classification.requiresCpuFallback());
        assertEquals(1, classification.flatTables().length);
        assertEquals(DfcOpenClRuntime.ExternalInputKind.FLAT_CACHE_2D, classification.slots()[0].kind());
        assertEquals(-1, classification.flatTables()[0].firstNoiseX());
        assertEquals(5, classification.flatTables()[0].firstNoiseZ());
    }

    @Test
    void directExternalSlotClassificationFallsBackForInvalidFlatCacheArray() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D}, 2, 0, 0));

        DfcOpenClRuntime.ExternalInputClassification classification =
                DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

        assertTrue(classification.requiresCpuFallback());
        assertEquals(DfcOpenClRuntime.ExternalInputKind.CPU_FALLBACK, classification.slots()[0].kind());
    }

    @Test
    void flatCache2dCpuPrefillMatchesExistingExternalComputePath() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        FlatCache2dDensityFunction extern = new FlatCache2dDensityFunction(
                new double[]{10.0D, 11.0D, 12.0D, 13.0D}, 2, 0, 7);
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(extern);
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        3, 0, 0, 0, 64, 28, 4, 2, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 32);
        DfcOpenClRuntime.ExternalInputClassification classification =
                DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});
        double[] actual = new double[request.n()];

        DfcOpenClRuntime.fillFlatCache2dSlotBufferInputsForTest(request, classification, actual);

        double[] rowMajor = DfcOpenClRuntime.fillExternalSlots(plan, request, 3);
        double[] expected = new double[request.n()];
        DfcOpenClRuntime.copyDirectExternalSlotBufferInputs(request, rowMajor, expected, new int[]{1}, new int[]{0});
        assertArrayEquals(expected, actual);
    }

    @Test
    void flatCache2dPrefillPacksTablesAndSlotMetadata() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, -1, 5));
        DfcOpenClRuntime.ExternalInputClassification classification =
                DfcOpenClRuntime.classifyDirectExternalSlotBufferInputs(plan, new int[]{1}, new int[]{0});

        DfcOpenClDeviceContext.FlatCache2dPrefill prefill = DfcOpenClRuntime.flatCache2dPrefill(classification);

        assertArrayEquals(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, prefill.flatValues());
        assertArrayEquals(new int[]{0}, prefill.slotCompactIndices());
        assertArrayEquals(new int[]{0}, prefill.slotTableIndices());
        assertArrayEquals(new int[]{0}, prefill.tableOffsets());
        assertArrayEquals(new int[]{2}, prefill.tableSides());
        assertArrayEquals(new int[]{-1}, prefill.tableFirstNoiseX());
        assertArrayEquals(new int[]{5}, prefill.tableFirstNoiseZ());
        assertEquals(1, prefill.slotCount());
    }

    @Test
    void finalOutputDirectExternalSlotBufferUsesFlatCache2dPrefillInsteadOfCpuCopy() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, 0, 0));
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        3, 0, 0, 0, 64, 0, 4, 2, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 32);
        double[] originalExternalSlotValues = new double[request.n() * request.slotCount()];
        Arrays.fill(originalExternalSlotValues, 99.0D);

        DfcOpenClRuntime.FinalOutputSlotBufferInputs inputs =
                DfcOpenClRuntime.fillFinalOutputSlotBufferInputsForTest(
                        plan, request, new boolean[]{false, true, false},
                        originalExternalSlotValues, new int[]{-1, 0, -1}, 1);

        assertNotNull(inputs.flatCache2dPrefill());
        assertArrayEquals(new double[request.n()], inputs.values());
        assertEquals("", inputs.flatCache2dFallbackReason());
    }

    @Test
    void directExternalSlotBufferInputsConstantIsFalseWhenFlatCache2dUploadIsNeeded() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = openClPlanWithOneExternal(
                new FlatCache2dDensityFunction(new double[]{1.0D, 2.0D, 3.0D, 4.0D}, 2, 0, 0));

        assertFalse(DfcOpenClRuntime.directExternalSlotBufferInputsConstant(plan, new int[]{1}));
    }

    @Test
    void externalPrefillTraceReportsFlatCache2dCounters() {
        String details = DfcOpenClRuntime.describeFlatCache2dPrefillForTest(
                new DfcOpenClDeviceContext.FlatCache2dPrefill(
                        new double[]{1.0D, 2.0D, 3.0D, 4.0D},
                        new int[]{0, 1}, new int[]{0, 0}, new int[]{0},
                        new int[]{2}, new int[]{0}, new int[]{0}, 2),
                "");

        assertTrue(details.contains("flatCache2dSlots=2"));
        assertTrue(details.contains("flatCache2dBuffers=1"));
        assertTrue(details.contains("flatCache2dBytes=32"));
    }

    @Test
    void directExternalSlotBufferInputsOnlyComputeDirectlyWhenAllExternsAreConstant() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan constantPlan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "constant-direct-externals",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, false, true},
                new int[]{-1, 0, -1, -1, 1},
                new DensityFunction[]{
                        new ConstantRangeDensityFunction(3.0D),
                        new ConstantRangeDensityFunction(-2.0D)
                },
                null);
        DfcOpenClRuntime.OpenClCompiledPlan dynamicPlan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "dynamic-direct-externals",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, false, true},
                new int[]{-1, 0, -1, -1, 1},
                new DensityFunction[]{
                        new ConstantRangeDensityFunction(3.0D),
                        new TestExternalDensityFunction(2_000.0D)
                },
                null);

        assertTrue(DfcOpenClRuntime.directExternalSlotBufferInputsConstant(constantPlan, new int[]{1, 4}));
        assertFalse(DfcOpenClRuntime.directExternalSlotBufferInputsConstant(dynamicPlan, new int[]{1, 4}));
    }

    @Test
    void compiledPlanExternalSlotIndicesRespectUsedSlotLimit() {
        assertArrayEquals(new int[]{0, 2}, DfcOpenClRuntime.compiledPlanExternalSlotIndices(
                new boolean[]{true, false, true, true}, 3));
        assertArrayEquals(new int[0], DfcOpenClRuntime.compiledPlanExternalSlotIndices(
                new boolean[]{true, false, true}, 0));
    }

    @Test
    void externalSlotPrefillComputesOnlyMarkerExternalSlotsInRowMajorOrder() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DfcOpenClRuntime.OpenClCompiledPlan plan = new DfcOpenClRuntime.OpenClCompiledPlan(
                "external-prefill",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[5],
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
                new boolean[]{false, true, false, true, false},
                new int[]{-1, 0, -1, 1, -1},
                new DensityFunction[]{
                        new TestExternalDensityFunction(1_000.0D),
                        new TestExternalDensityFunction(2_000.0D)
                },
                null);
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                        new byte[0], new double[0],
                        new byte[0], new double[0], new double[0], new double[0],
                        new int[0], new int[0], new double[0], new double[0],
                        5, 0, 0,
                        2, 10, 30,
                        1, 2, 1,
                        DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                        0.0D, new double[0], 2);

        double[] values = DfcOpenClRuntime.fillExternalSlots(plan, request, 5);

        assertArrayEquals(new double[]{
                0.0D, 4_112.0D, 0.0D, 5_112.0D, 0.0D,
                0.0D, 4_102.0D, 0.0D, 5_102.0D, 0.0D
        }, values);
    }

    @Test
    void residualDependencyNoiseBatchOnlySelectsUnresolvedNoiseSlots() {
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[6];
        computedSlots[4] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1}, new double[0], null, null, "computed4");

        boolean[] batch = DfcOpenClRuntime.residualDependencyNoiseBatchSlots(
                new boolean[]{false, true, true, true, true, false},
                new boolean[]{false, false, true, false, false, false},
                computedSlots,
                6);

        assertArrayEquals(new boolean[]{false, true, false, true, false, false}, batch);
    }

    @Test
    void finalOutputWaveTargetsFoldResidualNoiseDependencies() {
        DfcOpenClRuntime.ComputedSlot[] computedSlots = new DfcOpenClRuntime.ComputedSlot[6];
        computedSlots[4] = new DfcOpenClRuntime.ComputedSlot(
                new byte[]{2, 1}, new double[0], null, null, "computed4");

        boolean[] targets = DfcOpenClRuntime.finalOutputWaveTargetsWithResidualNoise(
                new boolean[]{false, true, false, false, false, false},
                new boolean[]{false, false, true, true, true, false},
                computedSlots,
                6);

        assertArrayEquals(new boolean[]{false, true, true, true, false, false}, targets);
    }

    @Test
    void finalOutputResidualNoiseFoldPolicyStagesHugeNoiseClosures() {
        assertTrue(DfcOpenClRuntime.shouldFoldFinalOutputResidualNoiseIntoWave(14_167, 32_768));
        assertFalse(DfcOpenClRuntime.shouldFoldFinalOutputResidualNoiseIntoWave(14_167, 65_536));
        assertTrue(DfcOpenClRuntime.shouldFoldFinalOutputResidualNoiseIntoWave(8_154, 65_536));
        assertTrue(DfcOpenClRuntime.shouldFoldFinalOutputResidualNoiseIntoWave(12_288, 65_536));
        assertFalse(DfcOpenClRuntime.shouldFoldFinalOutputResidualNoiseIntoWave(12_289, 65_536));
    }

    @Test
    void finalOutputSplitWaveTargetsFoldResidualNoiseIntoFirstNonEmptyWave() {
        boolean[][] targets = DfcOpenClRuntime.finalOutputSplitWaveTargetSlots(
                new boolean[][]{
                        new boolean[]{false, false, false},
                        new boolean[]{true, false, false},
                        new boolean[]{false, true, false}
                },
                new int[]{0, 2, 4},
                new int[]{1, 3, 5},
                new boolean[]{false, true, false, false, true, false},
                6);

        assertArrayEquals(new boolean[]{false, false, false, false, false, false}, targets[0]);
        assertArrayEquals(new boolean[]{true, true, false, false, true, false}, targets[1]);
        assertArrayEquals(new boolean[]{false, false, true, true, false, false}, targets[2]);
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
    void runtimeHybridColumnBatchMeetsDefaultMinimumWhenScheduledSlotsAreLargeEnough() {
        String oldMin = System.getProperty("dfc.opencl.finalDensityHybridMinSlotValues");
        try {
            System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            assertEquals(4_096, DfcOpenClRuntime.runtimeHybridBatchCellValues(4, 8, 32));
            assertEquals(221_184, DfcOpenClRuntime.runtimeHybridBatchSlotValues(4, 8, 32, 54));
            assertTrue(DfcOpenClRuntime.runtimeHybridBatchMeetsMinimum(4, 8, 32, 54));
            assertFalse(DfcOpenClRuntime.runtimeHybridBatchMeetsMinimum(4, 8, 1, 54));
        } finally {
            if (oldMin == null) {
                System.clearProperty("dfc.opencl.finalDensityHybridMinSlotValues");
            } else {
                System.setProperty("dfc.opencl.finalDensityHybridMinSlotValues", oldMin);
            }
        }
    }

    @Test
    void runtimeColumnBatchElementIndexOffsetsCells() {
        int cellWidth = 4;
        int cellHeight = 8;
        int cellVolume = cellWidth * cellWidth * cellHeight;
        assertEquals(0, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 0, cellWidth, cellHeight));
        assertEquals(16, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 1, cellWidth, cellHeight));
        assertEquals(127, DfcOpenClRuntime.runtimeColumnBatchElementIndex(0, 127, cellWidth, cellHeight));
        assertEquals(cellVolume, DfcOpenClRuntime.runtimeColumnBatchElementIndex(1, 0, cellWidth, cellHeight));
        assertEquals(cellVolume + 16, DfcOpenClRuntime.runtimeColumnBatchElementIndex(1, 1, cellWidth, cellHeight));
    }

    @Test
    void runtimeColumnBatchCopyExtractsOneJavaOrderCell() {
        int cellWidth = 4;
        int cellHeight = 8;
        int cellVolume = cellWidth * cellWidth * cellHeight;
        double[] batch = new double[cellVolume * 3];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 10_000.0D + i;
        }
        double[] cell = new double[cellVolume];

        DfcOpenClRuntime.copyRuntimeColumnBatchCell(batch, 2, cell, cellWidth, cellHeight);

        for (int i = 0; i < cellVolume; i++) {
            assertEquals(10_000.0D + cellVolume * 2 + i, cell[i]);
        }
    }

    @Test
    void runtimeCellGridCoordsKeepSyntheticXzLayout() {
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                testRequestWithLayout(DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ, 100, 40, 200, 4, 8, 64);

        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(0, request));
        assertEquals(47.0D, DfcOpenClRuntime.runtimeCellGridBlockY(0, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(0, request));
        assertEquals(104.0D, DfcOpenClRuntime.runtimeCellGridBlockX(128, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(128, request));
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(32 * 128, request));
        assertEquals(204.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(32 * 128, request));
    }

    @Test
    void runtimeCellGridCoordsMapYColumnLayout() {
        DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest request =
                testRequestWithLayout(DfcOpenClRuntime.CELL_GRID_LAYOUT_Y_COLUMN, 100, -64, 200, 4, 8, 32);

        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(0, request));
        assertEquals(-57.0D, DfcOpenClRuntime.runtimeCellGridBlockY(0, request));
        assertEquals(200.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(0, request));
        assertEquals(100.0D, DfcOpenClRuntime.runtimeCellGridBlockX(128, request));
        assertEquals(-49.0D, DfcOpenClRuntime.runtimeCellGridBlockY(128, request));
        assertEquals(203.0D, DfcOpenClRuntime.runtimeCellGridBlockZ(255, request));
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
    void runtimeHybridBatchStatsRecordSkipAttemptSuccessAndFailure() {
        DfcOpenClStats.reset();
        DfcOpenClStats.recordHybridBatchCall();
        DfcOpenClStats.recordHybridBatchSkipped("column batch disabled");
        DfcOpenClStats.recordHybridBatchAttempt(32, 4_096);
        DfcOpenClStats.recordHybridBatchSuccess(32, 4_096);
        DfcOpenClStats.recordHybridBatchFailure("device lost");

        DfcOpenClStats.Snapshot snapshot = DfcOpenClStats.snapshot();
        assertEquals(1L, snapshot.hybridBatchCalls());
        assertEquals(1L, snapshot.hybridBatchSkipped());
        assertEquals(1L, snapshot.hybridBatchAttempts());
        assertEquals(1L, snapshot.hybridBatchSucceeded());
        assertEquals(1L, snapshot.hybridBatchFailed());
        assertEquals(64L, snapshot.hybridBatchCells());
        assertEquals(8_192L, snapshot.hybridBatchElements());
        assertEquals("device lost", snapshot.hybridBatchLastSkip());

        DfcOpenClStats.reset();
        assertEquals(0L, DfcOpenClStats.snapshot().hybridBatchCalls());
        assertEquals("", DfcOpenClStats.snapshot().hybridBatchLastSkip());
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

    private static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest testRequestWithLayout(
            int layout, int firstBlockX, int firstBlockY, int firstBlockZ,
            int cellWidth, int cellHeight, int cells) {
        int n = cellWidth * cellWidth * cellHeight * cells;
        return new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                DfcOpenClSlabVmSmoke.bytecode(),
                DfcOpenClSlabVmSmoke.constants(),
                new byte[0],
                new double[0],
                new double[0],
                new double[0],
                new int[0],
                new int[0],
                new double[0],
                new double[0],
                0,
                0,
                0,
                firstBlockX,
                firstBlockY,
                firstBlockZ,
                cellWidth,
                cellHeight,
                cells,
                layout,
                0.0D,
                new double[n],
                n);
    }

    private static DfcOpenClRuntime.OpenClCompiledPlan openClPlanWithOneExternal(DensityFunction extern) {
        return new DfcOpenClRuntime.OpenClCompiledPlan(
                "one-external",
                new dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec[3],
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
                new boolean[]{false, true, false},
                new int[]{-1, 0, -1},
                new DensityFunction[]{extern},
                null);
    }

    private record TestExternalDensityFunction(double baseValue) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return this.baseValue + context.blockX() + context.blockY() * 10.0D + context.blockZ() * 100.0D;
        }

        @Override
        public double minValue() {
            return -1_000_000.0D;
        }

        @Override
        public double maxValue() {
            return 1_000_000.0D;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.zero().codec();
        }
    }

    private record ConstantRangeDensityFunction(double value) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            throw new AssertionError("constant external prefill should not call compute()");
        }

        @Override
        public double minValue() {
            return this.value;
        }

        @Override
        public double maxValue() {
            return this.value;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.zero().codec();
        }
    }

    private record FlatCacheLikeDensityFunction(double baseValue)
            implements DensityFunction.SimpleFunction, DfcCellCacheAccess {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return this.baseValue + context.blockX() + context.blockY() * 10.0D + context.blockZ() * 100.0D;
        }

        @Override
        public double minValue() {
            return 1.0D;
        }

        @Override
        public double maxValue() {
            return 1.0D;
        }

        @Override
        public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
            return compute(context);
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.zero().codec();
        }
    }

    private record FlatCache2dDensityFunction(double[] values, int side, int firstNoiseX, int firstNoiseZ)
            implements DensityFunction.SimpleFunction, DfcCellCacheAccess, NoiseChunk$FlatCache$FlatArray {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            int localX = (context.blockX() >> 2) - this.firstNoiseX;
            int localZ = (context.blockZ() >> 2) - this.firstNoiseZ;
            if (localX < 0 || localZ < 0 || localX >= this.side || localZ >= this.side) {
                return 0.0D;
            }
            return this.values[localX * this.side + localZ];
        }

        @Override
        public double minValue() {
            return Arrays.stream(this.values).min().orElse(0.0D);
        }

        @Override
        public double maxValue() {
            return Arrays.stream(this.values).max().orElse(0.0D);
        }

        @Override
        public double dfc$tryDirectRead(DensityFunction.FunctionContext context) {
            int localX = (context.blockX() >> 2) - this.firstNoiseX;
            int localZ = (context.blockZ() >> 2) - this.firstNoiseZ;
            if (localX < 0 || localZ < 0 || localX >= this.side || localZ >= this.side) {
                return DfcCacheFastPath.CACHE_MISS;
            }
            return this.values[localX * this.side + localZ];
        }

        @Override
        public double[] bts$getArray() {
            return this.values;
        }

        @Override
        public void bts$setArray(double[] value) {
            throw new UnsupportedOperationException("test fixture is immutable");
        }

        @Override
        public void bts$copyFlatArrayToVanillaValues() {
        }

        @Override
        public int bts$getSide() {
            return this.side;
        }

        @Override
        public int bts$getFirstNoiseX() {
            return this.firstNoiseX;
        }

        @Override
        public int bts$getFirstNoiseZ() {
            return this.firstNoiseZ;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.zero().codec();
        }
    }
}
